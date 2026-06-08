@id("org-members-can-read")
permit(
  principal,
  action == Action::"Stack::Read",
  resource
)
when {
  principal in resource.viewer_role
};

@id("users-can-read-self")
permit(
  principal,
  action == Action::"User::ReadSelf",
  resource
)
when {
  principal == resource
};
