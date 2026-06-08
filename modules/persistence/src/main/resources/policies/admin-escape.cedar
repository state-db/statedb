@id("admin-escape")
permit(
  principal in Group::"Admins",
  action,
  resource
);
