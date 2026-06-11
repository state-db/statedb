@id("member-write")
// Members may create, lock, delete, and encrypt stacks. The principal carries the
// Role it holds in THIS resource's org (resolved per request), so no org clause is
// needed. Owners inherit this via member ⊂ owner.
permit(
  principal in Role::"member",
  action in Action::"stack:WriteAccess",
  resource
);
