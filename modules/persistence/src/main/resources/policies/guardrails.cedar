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

@id("SiteAdministrationRequiresAdmin")
// Site administration remains a hard deployment-operator boundary. A future policy overlay may
// add permits, but Cedar's deny-overrides-allow semantics prevent it from delegating these actions
// beyond the env-controlled Role::"admin".
forbid(
  principal,
  action in Action::"site:AdminAccess",
  resource
)
unless { principal in Role::"admin" };
