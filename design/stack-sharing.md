# Stack Sharing & Visibility — Design Spec

Status: **engine implemented** (guest role, visibility, listing predicate, 404
collapse); the management surface (§7 shares API/console, visibility toggle) is
**pending**. Companion docs: [`auth.md`](auth.md) (Cedar model, roles,
`policy_grants`), [`db-schema.md`](db-schema.md), [`console-ux.md`](console-ux.md)
(S7/S11 surfaces this spec extends).

## 1. Requirement

Grant a principal access to a **single stack** rather than to everything in an
org — always *within* the org (cross-org sharing is out of scope, §12; the
console is org-scoped and a grantee is always an org member). Two distinct
product scenarios, with an asymmetry that picks the mechanism:

- **A — a contractor needs only specific stacks (additive).** The principal
  should start from *nothing* and have access enumerated grant by grant.
  Modeled by the `guest` membership tier (§4): membership with no default
  permissions, plus per-stack grants.
- **B — a stack too sensitive for ordinary members (subtractive).** The org
  keeps its defaults; *one stack* retracts the org-wide read. Modeled by
  `private` visibility (§5): invisible to members/viewers unless granted,
  always visible to owners.

The mechanisms compose: a guest can be granted a private stack; a member can be
granted a private stack; everything flows through the same `policy_grants`
rows.

## 2. Why the pre-guest model could not express scenario A

1. **The role floor was org-wide read.** `viewer.cedar` permits
   `stack:ReadOnlyAccess` to `Role::"viewer"`, and every human role inherits
   viewer (`viewer ⊂ member ⊂ owner`). Membership ⇒ read every stack in the
   org — there was no membership tier that saw *nothing*.
2. **The share templates guard on membership.** A grant applies only to a
   principal holding a role in the stack's org. Before the guest role, the
   lowest tier already read everything, so within-org read shares were
   redundant; scenario A had no principal to attach to.

The `guest` role fills exactly that hole. The guard itself is correct and
stays: it is what keeps tenancy (grants never escape the org) and instant
revocation (remove the membership, the grant is dead on the next request).

## 3. The listing invariant (constraint on every design here)

> A stack appears in a list **iff** the PDP would permit `stack:Read` on it
> for that principal — read visibility must remain **SQL-expressible** — and a
> direct request for a stack the principal cannot read collapses to **404**,
> never 403.

There is deliberately no `stack:List` action. List endpoints
(`GET /pulumi/api/user/stacks`, `GET /api/workspaces`) do not call Cedar per
row; they apply a SQL predicate that mirrors the read policies
(`StackListFinder`), while Cedar stays authoritative on detail routes.
Consequences:

- No static `forbid` may target an action inside `stack:ReadOnlyAccess`
  (guardrails stay write-only; today's only guardrail is on `stack:Delete`).
  Design B is therefore *not* a forbid — it inverts the viewer `permit`.
- Every policy feature that affects read ships with its SQL arm **and** extends
  the equivalence test (§9) that pins SQL row set == per-row PDP decisions.
- **404 collapse extends inside the org.** `StackEntityLoader` applies the same
  read-visibility predicate as the list finders: a stack the principal cannot
  read does not resolve (404), whether the principal is an outsider, a guest,
  or a member blocked by `private` visibility. A 403 would leak the existence
  and name of exactly the stacks being hidden. 403 remains reserved for
  within-visible-scope denials (e.g. a viewer attempting `stack:Delete` on a
  stack they can read).

## 4. Design A — `guest` role (additive-only membership)

A role *below* viewer that carries membership — tenancy, revocation-on-removal,
the template guard, `pulumi login` — but **no default permissions**.

| Piece | Change |
|---|---|
| `org_members.role` | CHECK extended to `('owner','member','viewer','service','guest')`; `guest` added to `OrgMember.Role` |
| `schema.cedar` | No structural change (roles are `entity Role in [Role]`); hierarchy is data |
| `Authorizer` role chain | `guest` is the new base of the human ladder: `viewer memberOf guest`, `member memberOf viewer`, `owner memberOf member`. So `principal in Role::"guest"` means "any human member, including guests", while `principal in Role::"viewer"` continues to exclude guests. `service` and `admin` still stand alone |
| Static policies | **Unchanged.** `viewer.cedar` still keys on `Role::"viewer"`, so a bare guest matches no policy and can do nothing |
| Share templates | Guard changes from `when { principal in Role::"viewer" }` to `when { principal in Role::"guest" }` — same purpose (grantee must be a member; grant dies with the membership), now inclusive of guests |
| `PrincipalRoleResolver` | Unchanged (resolves whatever role the membership row holds) |

Because `service` stands outside the guest chain, a `policy_grants` row naming
a service-account user is inert (the guard fails). Service accounts don't need
shares — see §5 for their visibility posture.

**Write shares carry read.** `StackWriteResourcePolicy` grants
`[stack:ReadOnlyAccess, stack:WriteAccess]`, not the write bundle alone. A
write share must be self-contained: its grantee may be a guest (no inherited
read) or the stack may be private (member read retracted), and a principal who
can `pulumi up` a stack must be able to read its checkpoint. The org-wide
managed template `StackWriteAccess` stays write-only — bundling read there
would silently bypass `private` visibility across the whole org. (Corollary:
a managed write grant is only useful to viewer+; to a guest it grants writes on
stacks they cannot see, which the 404 gate makes inert.)

### Grant flow

Owner adds the user to the org with role `guest`, then creates a
`policy_grants` row (`StackReadResourcePolicy` or `StackWriteResourcePolicy`,
`resource_stack_id` set), then the policy set reloads (§8). The guest can
`pulumi login`, sees exactly the granted stack(s) in every list, and can read —
or with a write grant, `pulumi up` — those stacks only. A write-granted guest
still cannot create stacks (`stack:Create` targets the Org and no policy
grants it to guests).

Revocation: deleting the membership row is **immediate** (`org_members` is
re-checked per request and the template guard fails); deleting only the grant
row closes the 404 gate **immediately** (the loader and list finders check
`policy_grants` per request) and retires the Cedar link on the next policy
reload (§8).

## 5. Design B — `private` visibility (hide from ordinary members)

Not a guardrail `forbid` on read — that is exactly the SQL-inexpressible
policy §3 bans. Instead, invert the default per stack. The default value is
**`internal`** (visible to the whole org's member tier — the GitHub/GitLab
sense of the word), the restricted value is **`private`**:

| Piece | Change |
|---|---|
| `stacks` | `visibility TEXT NOT NULL DEFAULT 'internal' CHECK (visibility IN ('internal','private'))` |
| `schema.cedar` | `entity Stack in [Project] { id: String, visibility: String }` |
| `StackEntityLoader` | Includes `visibility` in the Stack entity attributes; applies the §3 404 gate |
| `viewer.cedar` | Gains `when { resource.visibility == "internal" }` |
| `owner.cedar` | Unchanged — owners keep unconditional `stack:*`, so they always see private stacks. Members' read comes only via the viewer policy (member's own policy grants the write bundle, not read), so members/viewers lose sight of private stacks unless granted |
| `service.cedar` | **Deliberately unchanged** — service accounts retain org-wide access including private stacks. They are owner-minted org credentials driving CI; flipping a stack private must not break its pipeline, and shares cannot target them (§4). Anyone who can read CI secrets can therefore reach private state — document, don't pretend otherwise |

Access to a private stack = owners + service accounts + explicit
`policy_grants` shares (any membership tier, §4). Console: an owner-only
visibility toggle on the stack Settings tab (S7) and a `private` badge on list
rows — both pending (§7).

## 6. Secrets scope of a read share

`stack:ReadOnlyAccess` bundles `stack:Read` **and** `stack:DecryptSecrets` —
a read share today implies `pulumi stack export --show-secrets`. For a
contractor that is often wrong. Add a third template (pending, ships with §7):

- `StackReadNoSecretsPolicy` — identical to `StackReadResourcePolicy` but
  permits `action == Action::"stack:Read"` only (leaf, not the bundle).
- `policy_grants` CHECK constraint extends its accepted-template set
  (a deliberate migration, per the V009 design).
- The share-mint UI offers the choice at grant time: "read" vs
  "read incl. secrets" vs "write".

The console never decrypts, so this distinction only bites CLI access — which
is exactly where it matters. When this template ships it joins the
read-granting set in the listing predicate (§9) and the loader gate.

## 7. Share management surface (pending)

Grants and visibility are psql-managed today. Additions:

- **Who may share:** new leaf action `stack:ManageShares` in `stack:*` but
  **not** in `stack:WriteAccess` — so owners (and admins) manage shares,
  members do not; in particular a write-share grantee cannot re-share
  (`stack:*` membership would otherwise leak it via the write template's
  bundles — the template grants the two `*Access` bundles precisely to exclude
  future owner-only leaf actions like this one). Mirrors `serviceaccount:*`
  being owner-only.
- **Visibility toggle:** owner-only `stack:SetVisibility` (also in `stack:*`
  only), PATCH on the stack, S7 Settings toggle.
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
invisible to pod B until B rebuilds. Fix (ships with §7): a cheap staleness
poll — `@Scheduled` every ~10s, `SELECT max(created_at), count(*) FROM
policy_grants` (or a version row bumped on every grant write), reload on
change. Bounded staleness of seconds is acceptable for *granting*; the hard
revocation guarantees are per-request already — membership removal kills the
guard, grant-row deletion closes the 404 gate (§4). LISTEN/NOTIFY is the
upgrade path if the poll ever matters; don't start there.

## 9. Listing predicate & tests

Read visibility for a principal `:me`, applied identically by the list finders
(`StackListFinder`, backing both list endpoints) and — per §3 — by
`StackEntityLoader`'s 404 gate on detail routes:

```sql
WHERE
      -- owner.cedar / service.cedar: unconditional org-wide read
      EXISTS (SELECT 1 FROM org_members m WHERE m.user_id = :me
                AND m.org_id = s.org_id AND m.role IN ('owner','service'))
   OR -- viewer.cedar (member/viewer inherit): internal stacks only
      (s.visibility = 'internal'
       AND EXISTS (SELECT 1 FROM org_members m WHERE m.user_id = :me
                     AND m.org_id = s.org_id AND m.role IN ('member','viewer')))
   OR -- per-stack shares (read-granting templates), guarded on membership
      -- exactly as the Cedar guard is (any human tier incl. guest; not service)
      (EXISTS (SELECT 1 FROM policy_grants g WHERE g.principal_user_id = :me
                 AND g.resource_stack_id = s.id
                 AND g.template_id IN ('StackReadResourcePolicy',
                                       'StackWriteResourcePolicy'))
       AND EXISTS (SELECT 1 FROM org_members m WHERE m.user_id = :me
                     AND m.org_id = s.org_id
                     AND m.role IN ('owner','member','viewer','guest')))
```

Single-org credentials additionally clamp to their org (existing behavior).
Because visibility is a WHERE clause, keyset pagination composes unchanged
(cursor on the existing `org.name, project.name, stack.name` order). Admin
principals are out of scope for the predicate: listing remains
membership-driven for admins too (they browse other orgs via admin surfaces,
not `/api/workspaces`).

**Tests that hold the line:**

1. *Forbid lint* — scan the static policy set; assert no `forbid` targets an
   action in `stack:ReadOnlyAccess`.
2. *List/PDP equivalence* — seed orgs, members across all five roles, internal
   and private stacks, and grants of each template; assert the list finder's
   row set equals the set of stacks where the PDP permits `stack:Read`. This
   test is the invariant; every new policy feature must keep it green.
3. Role-floor regressions: bare guest sees nothing; guest + read grant sees
   exactly one stack (list + detail); member cannot see a private stack (404,
   not 403); owner and service see private stacks; revoked membership 404s
   immediately; write-granted guest can read/lock but not create or delete a
   share; a share to a non-member stays inert.

## 10. Migration summary

One migration (V015): `org_members` CHECK gains `'guest'`; `stacks` gains
`visibility` with default `'internal'`. Template/policy text changes (guards,
viewer `when`, write-template bundles) are classpath changes — a redeploy, no
migration. The pending §6/§7 work adds its own migration
(`policy_grants` CHECK gains `StackReadNoSecretsPolicy`).

## 11. Build order

1. **A1+B — engine** *(done)*: migration, guest role + role chain, template
   guard swap, write-share read bundle, visibility column + Cedar attribute,
   viewer `when`, loader 404 gate, listing predicate, tests 1–3. Ships both
   scenarios end-to-end via psql-managed grants and visibility.
2. **A2 — management surface** *(pending)*: `stack:ManageShares` +
   `stack:SetVisibility`, shares API, S7 Sharing card + visibility toggle,
   `StackReadNoSecretsPolicy`, grant-propagation poll (§8).

## 12. Out of scope

- Cross-org shares / shares without membership. The console is org-scoped and
  the guard's membership requirement is a feature: tenancy and instant
  revocation. (If ever revisited, the IAM precedent is that resource-policy
  grants need not appear in listings at all — access by explicit ref — so the
  listing invariant has a principled relaxation available. Not needed now.)
- Teams/groups as grantees; per-project grants; grant expiry.
- Changing the default read scope of existing roles (viewer stays org-wide for
  `internal` stacks).
- Decrypt audit logging (worth doing; separate concern).
