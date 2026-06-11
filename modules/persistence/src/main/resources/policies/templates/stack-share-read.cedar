// Resource-based policy — share read on ONE stack with one principal via a
// policy_grants row (template_id = "stack-share-read", principal_user_id,
// resource_stack_id). `resource == ?resource` pins it to that single stack. The
// `principal in Role::"viewer"` guard requires the grantee to be a member of the
// stack's org (req: resource policies only grant to org members) and neutralizes
// the grant if they leave. This is the IAM resource-based / S3-bucket-policy pattern.
permit(
  principal == ?principal,
  action in Action::"stack:ReadOnlyAccess",
  resource == ?resource
)
when { principal in Role::"viewer" };
