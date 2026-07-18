// Managed (identity-based) policy — attach to a principal via a policy_grants row
// (template_id = "StackWriteAccess", principal_user_id, org_id). Grants the
// stack write bundle across that one org: ?resource is bound to the Org, and
// `resource in ?resource` matches the Org itself plus every Stack/Project under it
// via the entity hierarchy. Deliberately write-only — bundling read here would
// bypass `private` stack visibility org-wide; useful to viewer+ (whose read comes
// from viewer.cedar), inert on stacks the grantee cannot read. The
// `principal in Role::"guest"` guard requires the grantee to be a human member of
// that org (any human tier ⊆ guest), and neutralizes the grant if they later
// leave. Attaching the same template to many principals is the IAM "managed
// policy" pattern.
permit(
  principal == ?principal,
  action in Action::"stack:WriteAccess",
  resource in ?resource
)
when { principal in Role::"guest" };
