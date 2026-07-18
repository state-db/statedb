# Stack Sharing & Visibility — Design Spec

Status: **proposed** (not yet sliced). Companion docs: [`auth.md`](auth.md)
(Cedar model, roles, `policy_grants`), [`db-schema.md`](db-schema.md),
[`console-ux.md`](console-ux.md) (S7/S11 surfaces this spec extends).

## 1. Requirement

Grant a principal access to a **single stack** rather than to everything in an
org. Two distinct product scenarios hide inside that sentence:

- **A — an outsider needs one stack.** A contractor, an auditor, or a CI
  identity should see (or update) exactly one stack, and nothing else in the
  org.
- **B — a stack too sensitive for ordinary members.** A prod-secrets or
  restricted-project stack should be invisible to the org at large and
  reachable only by its owners and explicit grantees.

Both must preserve the listing contract in §3.

## 2. Why the current model cannot express this

Two facts, verified against the shipped policy set:

1. **The role floor is org-wide read.** `viewer.cedar` permits
   `stack:ReadOnlyAccess` to `Role::"viewer"`, and every org member holds at
   least viewer (`viewer ⊂ member ⊂ owner`, assembled in `Authorizer`'s role
   entity chain). Membership ⇒ read every stack in the org.
2. **The per-stack share templates are inert.** `StackReadResourcePolicy` /
   `StackWriteResourcePolicy` (linked via `policy_grants`) carry the guard
   `when { principal in Role::"viewer" }` — the grantee must hold a role in the
   stack's org. So inside the org a share is redundant (members already read
   everything), and outside the org it grants nothing (no role ⇒ guard fails).

"Give someone access to a special stack" therefore has no principal it can
apply to today. The `policy_grants` machinery is the right primitive; what's
missing is a membership tier it can meaningfully attach to (§4) and, for
scenario B, a way to retract the org-wide default per stack (§5).

## 3. The listing invariant (constraint on every design here)

> A stack appears in a list **iff** the PDP would permit `stack:Read` on it
> for that principal — and read visibility must remain **SQL-expressible**.

There is deliberately no `stack:List` action. List endpoints
(`GET /pulumi/api/user/stacks`, `GET /api/workspaces`) do not call Cedar per
row; they apply a SQL predicate that mirrors the read policies
(`StackListFinder` / `WorkspaceListFinder`), while Cedar stays authoritative on
detail routes. Consequences:

- No static `forbid` may target an action inside `stack:ReadOnlyAccess`
  (guardrails stay write-only; today's only guardrail is on `stack:Delete`).
- Every policy feature that affects read must ship with its SQL arm **and**
  extend the equivalence test (§9) that pins SQL row set == per-row PDP
  decisions.

Both designs below keep the deciding data (role, grant rows, visibility flag)
in DB columns precisely so this invariant survives.

## 4. Design A — `guest` role (share with an outsider)

A role *below* viewer that carries membership — tenancy, revocation-on-removal,
the template guard — but **no default permissions**.

### Changes

| Piece | Change |
|---|---|
| `org_members.role` | Migration: extend the CHECK to `('owner','member','viewer','guest')`; add `guest` to `OrgMember.Role` |
| `schema.cedar` | No structural change (roles are `entity Role in [Role]`); hierarchy is data |
| `Authorizer` role chain | Extend to `guest`, `viewer memberOf guest`, `member memberOf viewer`, `owner memberOf member` — so `principal in Role::"guest"` means "any member, including guests", while `principal in Role::"viewer"` continues to exclude guests |
| Static policies | **Unchanged.** `viewer.cedar` still keys on `Role::"viewer"`, so a bare guest can do nothing |
| Share templates | Guard changes from `when { principal in Role::"viewer" }` to `when { principal in Role::"guest" }`. Its purpose — grantee must be a member; grant dies when membership is removed — is preserved |
| `PrincipalRoleResolver` | Unchanged (resolves whatever role the membership row holds) |

### Grant flow

Owner adds the user to the org with role `guest`, then creates a
`policy_grants` row (`StackReadResourcePolicy` or `StackWriteResourcePolicy`,
`resource_stack_id` set), then the policy set reloads (§8). The guest can
`pulumi login`, sees exactly the granted stack(s) in every list, and can read —
or with a write grant, `pulumi up` — that stack only. A write-granted guest
still cannot create stacks (`stack:Create` targets the Org and no policy
grants it to guests).

Revocation: deleting the membership row is **immediate** (`org_members` is
re-checked per request and the template guard fails); deleting only the grant
row takes effect on the next policy reload (§8).

## 5. Design B — `private` visibility (hide from ordinary members)

Do **not** model this as a guardrail `forbid` on read — that is exactly the
SQL-inexpressible policy §3 bans. Instead, invert the default per stack:

| Piece | Change |
|---|---|
| `stacks` | Migration: `visibility TEXT NOT NULL DEFAULT 'shared' CHECK (visibility IN ('shared','private'))` |
| `schema.cedar` | `entity Stack in [Project] { id: String, visibility: String }` |
| `StackEntityLoader` | Include `visibility` in the Stack entity attributes |
| `viewer.cedar` | Gains `when { resource.visibility == "shared" }` |
| `owner.cedar` | Unchanged — owners keep unconditional `stack:*`, so they always see private stacks. Members' read comes only via the viewer policy (member's own policy grants the write bundle, not read), so members/viewers lose sight of private stacks unless granted |

Access to a private stack = owners + explicit `policy_grants` shares (which,
after §4, can target any membership tier). Console: an owner-only visibility
toggle on the stack Settings tab (S7) and a `private` badge on list rows.

## 6. Secrets scope of a read share

`stack:ReadOnlyAccess` bundles `stack:Read` **and** `stack:DecryptSecrets` —
a read share today implies `pulumi stack export --show-secrets`. For a
contractor that is often wrong. Add a third template:

- `StackReadNoSecretsPolicy` — identical to `StackReadResourcePolicy` but
  permits `action == Action::"stack:Read"` only (leaf, not the bundle).
- `policy_grants` CHECK constraint extends its accepted-template set
  (a deliberate migration, per the V009 design).
- The share-mint UI offers the choice at grant time: "read" vs
  "read incl. secrets" vs "write".

The console never decrypts, so this distinction only bites CLI access — which
is exactly where it matters.

## 7. Share management surface

No API or screen exists today; grants are psql-only. Additions:

- **Who may share:** new leaf action `stack:ManageShares` in `stack:*` but
  **not** in `stack:WriteAccess` — so owners (and admins) manage shares,
  members do not. This mirrors `serviceaccount:*` being owner-only.
- **API:** `GET/POST/DELETE /api/stacks/{org}/{project}/{stack}/shares`
  (console session auth, `@Authorize(action = "stack:ManageShares",
  loader = StackEntityLoader)`). POST body: grantee user id + template choice
  (§6). Membership of the grantee in the stack's org is validated at mint
  (offering to add them as `guest` if absent is a console affordance, not an
  API side effect).
- **Console:** a "Sharing" card on the stack Settings tab (S7) listing grants
  (grantee, scope, created, revoke), plus grant visibility in the admin org
  panel (S11). Add both to `console-ux.md`'s API-gap table when sliced.

## 8. Multi-pod grant propagation

`PolicySetCache.reload()` is per-pod and in-memory; a grant written on pod A is
invisible to pod B until B rebuilds. Fix: a cheap staleness poll — `@Scheduled`
every ~10s, `SELECT max(created_at), count(*) FROM policy_grants` (or a
version row bumped on every grant write), reload on change. Bounded staleness
of seconds is acceptable for *granting*; the hard revocation guarantee rides on
membership removal, which is already per-request (§4). LISTEN/NOTIFY is the
upgrade path if the poll ever matters; don't start there.

## 9. Listing predicate & tests

The visibility predicate all list finders share (arms annotated with the
design that activates them):

```sql
WHERE
      -- role policies: viewer+ ⇒ org-wide read of shared stacks
      (s.org_id IN (SELECT org_id FROM org_members
                     WHERE user_id = :me AND role IN ('owner','member','viewer'))
       AND (s.visibility = 'shared'                          -- Design B
            OR EXISTS (SELECT 1 FROM org_members
                        WHERE user_id = :me AND org_id = s.org_id
                          AND role = 'owner')))
   OR -- per-stack shares (any read-granting template)       -- Design A
      s.id IN (SELECT resource_stack_id FROM policy_grants
                WHERE principal_user_id = :me
                  AND template_id IN ('StackReadResourcePolicy',
                                      'StackReadNoSecretsPolicy',
                                      'StackWriteResourcePolicy'))
```

Single-org credentials additionally clamp to their org (existing behavior).
Because visibility is a WHERE clause, keyset pagination composes unchanged
(cursor on the existing `org.name, project.name, stack.name` order).

**Tests that hold the line:**

1. *Forbid lint* — parse the static policy set; assert no `forbid` targets an
   action in `stack:ReadOnlyAccess`.
2. *List/PDP equivalence* — seed orgs, members across all four roles, private
   stacks, and grants of each template; assert the list finders' row sets
   equal the set of stacks where the PDP permits `stack:Read`. This test is
   the invariant; every new policy feature must keep it green.
3. Role-floor regressions: bare guest sees nothing; guest + read grant sees
   exactly one stack (list + detail + export); revoked membership 404s
   immediately; write-granted guest can lock/update but not create or share.

## 10. Migration summary

| Migration | Contents |
|---|---|
| A | `org_members` CHECK gains `'guest'`; `policy_grants` CHECK gains `StackReadNoSecretsPolicy` |
| B | `stacks.visibility` column + default `'shared'` |

Template/policy text changes (guards, viewer `when`, new template, new action)
are classpath changes — a redeploy, no migration.

## 11. Build order

1. **A1 — guest role + template guards + listing arm** (migration A, role
   chain, guard swap, finder predicate, tests 1–3). Ships the whole outsider
   scenario end-to-end via psql-managed grants.
2. **A2 — share management** (`stack:ManageShares`, shares API, S7 card,
   grant-propagation poll).
3. **B — private visibility** (migration B, schema attr, viewer `when`,
   loader attr, S7 toggle). Independent of A2; requires A1's listing arm only
   if private stacks should be share-able to guests.

## 12. Out of scope

- Cross-org shares without membership (the guard's membership requirement is
  a feature: tenancy and instant revocation).
- Teams/groups as grantees; per-project grants; grant expiry.
- Changing the default read scope of existing roles (viewer stays org-wide).
- Decrypt audit logging (worth doing; separate concern).
