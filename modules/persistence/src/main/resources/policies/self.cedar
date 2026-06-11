@id("users-can-read-self")
// Any authenticated principal may read its own user record.
permit(
  principal,
  action == Action::"user:ReadSelf",
  resource
)
when { principal == resource };
