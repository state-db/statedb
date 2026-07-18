// Managed (identity-based) policy — attach to a principal via a policy_grants row
// (template_id = "StackWriteAccess", principal_user_id, org_id). Grants the
// stack write bundle across that one org: ?resource is bound to the Org, and
// `resource in ?resource` matches the Org itself plus every Stack/Project under it
// via the entity hierarchy. Deliberately write-only — bundling read here would
// bypass `private` stack visibility org-wide.
//
// The guard stays at Role::"viewer" (NOT the guest-inclusive Role::"guest" the
// per-stack share templates use): because ?resource is the Org, this template
// matches `stack:Create` on the Org resource, where no stack-read 404 gate
// applies — a guest-inclusive guard would let a guest create stacks. Guests get
// per-stack shares only; org-wide write is a viewer+ elevation. The guard also
// requires the grantee to be a member and neutralizes the grant when they leave.
permit(
  principal == ?principal,
  action in Action::"stack:WriteAccess",
  resource in ?resource
)
when { principal in Role::"viewer" };
