// Roles are global and inherit, IAM-style: guest ⊂ viewer ⊂ member ⊂ owner.
// `guest` is the additive-only base tier — it matches no static policy; the
// share-template guard keys on it ("any human member"). `admin` is the
// break-glass role and inherits from nothing.
entity Role in [Role];

// A user is a global identity. Its role is resolved per request against the
// resource's org and attached as a Role parent — see PrincipalRoleResolver.
entity User in [Role];

// The singleton state-db deployment. Global console administration has no org-relative
// resource, so these decisions target Site::"statedb:site:root".
entity Site { id: String };

// Every resource carries its fully-qualified name (== its entity id) as an `id`
// attribute, so policies can pattern-match it IAM-style — e.g. a Deny on
// `resource.id like "statedb:stack:*/prod"`. Tenancy itself is still the resolved
// role, and managed grants scope through the Stack -> Project -> Org hierarchy; the
// name is for resource-pattern matching, not for tenancy.
entity Org { id: String };

entity Project in [Org] { id: String };

// `visibility` ("internal" | "private") gates the viewer policy's org-wide read;
// mirrored in SQL by the list finders and StackEntityLoader (design/stack-sharing.md).
entity Stack in [Project] { id: String, visibility: String };

// Global console administration. Leaf actions remain distinct for decision auditing; the bundle
// gives the non-delegable site-admin guardrail one closed action set to protect.
action "site:AdminAccess";

action "provider:Manage" in ["site:AdminAccess"] appliesTo {
  principal: [User],
  resource: [Site]
};

action "org:Admin" in ["site:AdminAccess"] appliesTo {
  principal: [User],
  resource: [Site]
};

action "user:ReadOthers" in ["site:AdminAccess"] appliesTo {
  principal: [User],
  resource: [Site]
};

action "operations:Read" in ["site:AdminAccess"] appliesTo {
  principal: [User],
  resource: [Site]
};

action "entitlement:Read" in ["site:AdminAccess"] appliesTo {
  principal: [User],
  resource: [Site]
};

// Actions are named IAM-style as `service:Operation`. `stack:*` is the all-of-
// service wildcard (≈ IAM `"Action": "stack:*"`); the two `*Access` actions are
// pre-canned bundles (≈ AWS managed-policy action sets). All three are action
// groups: a policy grants the group once with `action in Action::"stack:*"` /
// `action in Action::"stack:WriteAccess"` instead of listing every member.
// Membership chains upward, leaf -> bundle -> `stack:*`, via `in [...]`.
action "stack:*";
action "stack:ReadOnlyAccess" in ["stack:*"];
action "stack:WriteAccess" in ["stack:*"];

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

action "stack:SetTags" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

action "stack:Rename" in ["stack:WriteAccess"] appliesTo {
  principal: [User],
  resource: [Stack]
};

// Console-only operational actions. They are intentionally outside the write-access
// bundle, so only the platform-admin policy's unrestricted permit grants them.
action "stack:Unlock" appliesTo {
  principal: [User],
  resource: [Stack]
};

action "compaction:Trigger" appliesTo {
  principal: [User],
  resource: [Stack]
};

// Service-account (CI machine identity) management. Org-level operations: minting
// a service account creates a machine user + membership + token inside one org.
// Granted to owners (owner.cedar) and admins (admin.cedar's unconstrained action).
action "serviceaccount:*";

action "serviceaccount:Create" in ["serviceaccount:*"] appliesTo {
  principal: [User],
  resource: [Org]
};

action "serviceaccount:List" in ["serviceaccount:*"] appliesTo {
  principal: [User],
  resource: [Org]
};

action "serviceaccount:Revoke" in ["serviceaccount:*"] appliesTo {
  principal: [User],
  resource: [Org]
};

action "org:ManageMembers" appliesTo {
  principal: [User],
  resource: [Org]
};
