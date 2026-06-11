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
