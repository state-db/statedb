@id("org-write")
// Slice 18 keeps writes coarse-grained: org members can create, lock, delete, and encrypt stacks.
// The full owner-only delete matrix is intentionally deferred.
permit(
  principal,
  action in [
    Action::"Stack::Create",
    Action::"Stack::Lock",
    Action::"Stack::Delete",
    Action::"Stack::EncryptSecrets"
  ],
  resource
)
when {
  principal in resource.member_role
};
