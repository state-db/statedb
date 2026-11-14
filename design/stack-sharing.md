# Stack visibility and grants

Two mechanisms narrow or widen stack access inside an organization. The `guest`
role is additive: a guest is a member with no default permissions, whose access
is exactly the stacks granted to them. `private` visibility is subtractive: it
withdraws one stack from the organization-wide read that viewers and members
otherwise have. Both use the same per-stack grants. Grants and visibility are
enforced throughout, but there is no API or console workflow to change them yet
(see [Current limits](#current-limits)).

## Visibility

Every stack has `internal` or `private` visibility, and new stacks default to
`internal`. Both values describe access within the organization; there is no
anonymous public access.

| Principal | Internal stack | Private stack |
| --- | --- | --- |
| Site administrator | Read | Read |
| Organization owner | Read | Read |
| Service account in the organization | Read | Read |
| Member or viewer | Read | Explicit grant required |
| Guest | Explicit grant required | Explicit grant required |
| Non-member | No access | No access |

Private visibility does not hide a stack from owners or service accounts.
Service accounts keep access so that marking a stack private never breaks the
pipeline that deploys it. As a consequence, anyone who can read a CI secret can
reach private state.

## Listing invariant

A stack appears in a caller's inventory exactly when Cedar would permit
`stack:Read` on it. There is no `stack:List` action: the list endpoints
(`GET /pulumi/api/user/stacks` and the console workspace list) filter with a
SQL predicate before pagination instead of calling Cedar per row, which would
produce short pages and wrong counts.

Three implementations of the read rule must agree: the SQL in `StackListFinder`,
the in-memory `StackReadVisibility` predicate used by `StackEntityLoader`, and
the Cedar read policies. `StackSharingVisibilityTest` checks that they produce
the same results. Any policy change that affects read needs a matching SQL change
and test coverage.

The rule must stay expressible in SQL, so no static `forbid` may target a read
action. `PolicyForbidLintTest` enforces this, and it is why `private` visibility
is a condition on the viewer `permit` rather than a deny.

`StackEntityLoader` treats an unreadable stack as absent and returns 404, for
outsiders, guests without a grant, and members blocked by private visibility
alike. A 403 would confirm that the hidden stack exists. A denied action on a
readable stack, such as a viewer deleting a stack, returns 403.

Site administrators are the one exception to the invariant. They can read any
stack directly, but inventory listing is membership-driven for them too, so a
stack appears in their list only through their own memberships and grants.

## Grant types

Each `policy_grants` row links a user to one of three Cedar templates:

| Template | Resource | Authority |
| --- | --- | --- |
| `StackReadResourcePolicy` | One stack | Read and decrypt |
| `StackWriteResourcePolicy` | One stack | Read and write |
| `StackWriteAccess` | One organization | Write actions on every stack in it |

A per-stack write grant includes read, because whoever can run `pulumi up` on a
stack must be able to read its checkpoint, and the grantee may be a guest or the
stack may be private.

The organization-wide `StackWriteAccess` template grants write only, since
adding read would bypass private visibility across the organization. It also
requires a viewer or higher role: its resource is the organization, so it
matches `stack:Create`, where no stack-level 404 check applies, and a
guest-inclusive guard would let guests create stacks.

Every template requires the grantee to hold a human role in the stack's
organization, guest included. Grants to non-members or service accounts have no
effect. A database constraint fixes which templates exist and whether each has a
stack, and a composite foreign key keeps a granted stack in the grant's
organization.

Removing a membership neutralizes the user's grants on the next request, because
the template condition fails. The grant rows stay, so restoring the
membership reactivates them. Deleting the stack, the user, or the organization
deletes the rows.

## Secrets

Read access includes `stack:DecryptSecrets`, so a read grant lets the grantee
recover service-managed secret plaintext through the CLI. The console never
decrypts, and its masking does not narrow that authority.

Stacks that use an external secrets provider keep that provider's boundary:
ciphertext read from state-db is useless without the external key.

## Current limits

There is no API or console workflow to create or revoke grants or to change a
stack's visibility. The organization member view shows a guest's existing
grants.

`PolicySetCache` reads grant links at startup and has an in-process `reload()`
method, but nothing in the application calls it and there is no cross-replica
invalidation. The list endpoints and the 404 check read `policy_grants` on each
request, while Cedar uses the links compiled at startup. After a direct database
edit, a new grant makes the stack visible in lists while Cedar still denies the
actions it grants, and a deleted write grant keeps working on a stack the user
can still read, until each replica restarts. Direct database edits are therefore not a supported way to manage
grants, and a management path must reload every replica after each committed
change.

## Implementation

The relevant code is [StackReadVisibility](../modules/persistence/src/main/java/app/statedb/persistence/authz/StackReadVisibility.java),
[StackListFinder](../modules/persistence/src/main/java/app/statedb/persistence/stack/StackListFinder.java),
[PolicySetCache](../modules/persistence/src/main/java/app/statedb/persistence/policy/PolicySetCache.java),
and the [Cedar templates](../modules/persistence/src/main/resources/policies/templates/).
