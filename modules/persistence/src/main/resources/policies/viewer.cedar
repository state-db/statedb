@id("ReadOnlyAccess")
// AWS ReadOnlyAccess analog: read stacks (and decrypt their secrets). The principal carries the
// Role it holds in THIS resource's org (resolved per request), so no org clause
// is needed. Members and owners inherit this via viewer ⊂ member ⊂ owner.
// The `when` gates the org-wide read to internal stacks: private stacks are
// readable only by owners (unconditional stack:*), service accounts, and
// explicit policy_grants grantees. Guests sit below viewer and never match this
// policy at all. See design/stack-sharing.md.
permit(
  principal in Role::"viewer",
  action in Action::"stack:ReadOnlyAccess",
  resource
)
when { resource.visibility == "internal" };
