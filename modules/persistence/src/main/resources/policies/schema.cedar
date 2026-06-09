entity Group;

entity OrgRole in [OrgRole];

entity User in [OrgRole, Group];

entity Org = {
  name: String,
  viewer_role: OrgRole,
  member_role: OrgRole,
  owner_role: OrgRole,
};

entity Project in [Org] = {
  org_name: String,
  name: String,
};

entity Stack in [Project] = {
  org_name: String,
  project_name: String,
  name: String,
  viewer_role: OrgRole,
  member_role: OrgRole,
  owner_role: OrgRole,
};

action "User::ReadSelf" appliesTo {
  principal: [User],
  resource: [User]
};

action "Stack::Read" appliesTo {
  principal: [User],
  resource: [Stack]
};

action "Stack::DecryptSecrets" appliesTo {
  principal: [User],
  resource: [Stack]
};

action "Stack::EncryptSecrets" appliesTo {
  principal: [User],
  resource: [Stack]
};

action "Stack::Lock" appliesTo {
  principal: [User],
  resource: [Stack]
};

action "Stack::Create" appliesTo {
  principal: [User],
  resource: [Org]
};

action "Stack::Delete" appliesTo {
  principal: [User],
  resource: [Stack]
};
