@id("ReadOnlyAccess")
// AWS ReadOnlyAccess analog: read stacks (and decrypt their secrets). The principal carries the
// Role it holds in THIS resource's org (resolved per request), so no org clause
// is needed. Members and owners inherit this via viewer ⊂ member ⊂ owner.
permit(
  principal in Role::"viewer",
  action in Action::"stack:ReadOnlyAccess",
  resource
);
