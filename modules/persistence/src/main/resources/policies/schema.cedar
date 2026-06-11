// Roles are global and inherit, IAM-style: viewer ⊂ member ⊂ owner.
// `admin` is the break-glass role and inherits from nothing.
entity Role in [Role];

// A user is a global identity. Its role is resolved per request against the
// resource's org and attached as a Role parent — see PrincipalRoleResolver.
entity User in [Role];

// Resources carry no attributes: org-scoping is the resolved role, and managed
// grants scope through the Stack -> Project -> Org hierarchy.
entity Org;

entity Project in [Org];

entity Stack in [Project];

// Actions are named IAM-style as `resource:Operation`. The two `*Access`
// actions are pre-canned bundles (action groups): a policy can grant the
// bundle once with `action in Action::"stack:WriteAccess"` instead of listing
// every member. Membership is declared on each leaf with `in [...]`.
action "stack:ReadOnlyAccess";
action "stack:WriteAccess";

action "stack:Read" in ["stack:ReadOnlyAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "stack:DecryptSecrets" in ["stack:ReadOnlyAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "stack:Create" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Org]
};

action "stack:Lock" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "stack:Delete" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "stack:EncryptSecrets" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "user:ReadSelf" appliesTo {
  principal: [User],
  resource: [User]
};
