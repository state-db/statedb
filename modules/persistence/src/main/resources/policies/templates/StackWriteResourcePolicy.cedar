// Resource-based policy — share write on ONE stack with one principal via a
// policy_grants row (template_id = "StackWriteResourcePolicy", principal_user_id,
// resource_stack_id). Elevates a member (typically a viewer, who otherwise only
// reads) to write a single stack without org-wide write. `resource == ?resource`
// pins it to that stack; the `principal in Role::"viewer"` guard requires the
// grantee to be a member of the stack's org and neutralizes the grant if they leave.
permit(
  principal == ?principal,
  action in Action::"stack:WriteAccess",
  resource == ?resource
)
when { principal in Role::"viewer" };
