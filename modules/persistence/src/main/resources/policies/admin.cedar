@id("AdministratorAccess")
// AWS AdministratorAccess analog: every action on every resource (the IAM
// `"Action": "*", "Resource": "*"` grant), with no org boundary. Break-glass —
// attached to the admin role. Note an explicit Deny (see guardrails.cedar) still
// overrides this, exactly as in IAM.
permit(
  principal in Role::"admin",
  action,
  resource
);
