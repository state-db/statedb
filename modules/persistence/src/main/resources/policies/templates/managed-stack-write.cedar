// Managed (identity-based) policy — attach to a principal via a policy_grants row
// (template_id = "managed-stack-write", principal_user_id, org_id). Grants the
// stack write bundle across that one org: ?resource is bound to the Org, and
// `resource in ?resource` matches the Org itself plus every Stack/Project under it
// via the entity hierarchy. The `principal in Role::"viewer"` guard requires the
// grantee to be a member of that org (any role ⊆ viewer), and neutralizes the
// grant if they later leave. Attaching the same template to many principals is the
// IAM "managed policy" pattern.
permit(
  principal == ?principal,
  action in Action::"stack:WriteAccess",
  resource in ?resource
)
when { principal in Role::"viewer" };
