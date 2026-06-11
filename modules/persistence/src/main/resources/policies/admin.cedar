@id("admin-full-access")
// Break-glass administrator (≈ AWS AdministratorAccess): every action on every
// resource, with no org boundary.
permit(
  principal in Role::"admin",
  action,
  resource
);
