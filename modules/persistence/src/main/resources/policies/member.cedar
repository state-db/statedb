@id("PowerUserAccess")
// AWS PowerUserAccess analog: create, lock, delete, and encrypt stacks (the write
// bundle), on top of the read the member role inherits from viewer. The principal carries the
// Role it holds in THIS resource's org (resolved per request), so no org clause is
// needed. Owners inherit this via member ⊂ owner.
permit(
  principal in Role::"member",
  action in Action::"stack:WriteAccess",
  resource
);
