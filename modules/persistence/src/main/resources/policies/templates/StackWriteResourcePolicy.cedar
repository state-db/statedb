// Resource-based policy — share read + write on ONE stack with one principal via
// a policy_grants row (template_id = "StackWriteResourcePolicy", principal_user_id,
// resource_stack_id). Carries the read bundle alongside the write bundle so the
// share is self-contained: the grantee may be a guest (no inherited read) or the
// stack may be private (member read retracted), and whoever can `pulumi up` a
// stack must be able to read its checkpoint. `resource == ?resource` pins it to
// that stack; the `principal in Role::"guest"` guard requires the grantee to be a
// human member of the stack's org and neutralizes the grant if they leave.
permit(
  principal == ?principal,
  action in [Action::"stack:ReadOnlyAccess", Action::"stack:WriteAccess"],
  resource == ?resource
)
when { principal in Role::"guest" };
