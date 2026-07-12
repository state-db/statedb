@id("StackFullAccess")
// AWS service-FullAccess analog (≈ AmazonS3FullAccess): full access to the stack
// "service" in one statement, via the `stack:*` action wildcard. Owners inherit
// read + write through the role hierarchy too; this makes their all-of-service
// access first-class and shows the IAM `"Action": "stack:*"` authoring idiom. The
// principal carries the Role it holds in THIS resource's org, so no org clause is
// needed.
permit(
  principal in Role::"owner",
  action in Action::"stack:*",
  resource
);

@id("ServiceAccountAdmin")
// Only owners manage the org's CI machine identities. A service account can act
// with its own token but never mint, list, or revoke accounts (Role::"service" is
// not owner), so a leaked CI token cannot self-replicate.
permit(
  principal in Role::"owner",
  action in Action::"serviceaccount:*",
  resource
);
