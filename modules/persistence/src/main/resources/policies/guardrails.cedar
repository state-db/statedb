@id("DenyDeleteProtectedStacks")
// Explicit Deny — an org-wide guardrail (≈ an AWS SCP / permission-boundary Deny).
// Cedar `forbid` always wins over every `permit`, so this overrides even
// AdministratorAccess: no principal, whatever its role or grants, may delete a
// stack named "prod". This is IAM's signature deny-overrides-allow rule, matched
// the IAM way — by resource-name pattern (`resource.id like ...`) rather than by role.
forbid(
  principal,
  action == Action::"stack:Delete",
  resource
)
when { resource.id like "statedb:stack:*/prod" };
