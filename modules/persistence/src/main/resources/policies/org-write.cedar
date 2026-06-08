@id("org-write")
// Slice 18 keeps writes coarse-grained: org members can create, lock, and delete stacks.
// The full owner-only delete matrix is intentionally deferred.
permit(
  principal,
  action in [Action::"Stack::Create", Action::"Stack::Lock", Action::"Stack::Delete"],
  resource
)
when {
  principal in resource.member_role
};
