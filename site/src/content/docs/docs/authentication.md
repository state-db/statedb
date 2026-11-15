---
title: Authentication and access
description: How people and automation authenticate to statedb, and how access is decided.
---

statedb separates two kinds of identity. People sign in through your OpenID
Connect provider and get a session; automation authenticates with a service
account token and never touches a browser. Both end up at the same
authorization check.

This page covers the model. For the console screens that carry it out, see
[using the console](/docs/management/).

## How access is decided

Every identity belongs to one or more organizations, and its role there is the
starting point:

| Role | Intent |
| --- | --- |
| `owner` | Full control of the organization, including membership |
| `member` | Day-to-day use of the organization's stacks |
| `viewer` | Read-only access |
| `guest` | Tenancy only; access comes entirely from explicit stack grants |
| `service` | Machine identity for CI, held by service accounts |

The first four form a ladder, each inheriting the one below it. `guest` sits at
the bottom and matches no policy on its own: a guest can sign in and belongs to
the organization, but sees exactly the stacks they have been granted and nothing
else. That makes it the right role for someone who should see one stack without
seeing the estate around it.

`service` sits outside the ladder deliberately. Service accounts have their own
policy set, so tightening what CI can do never reshapes what people can do.

Per-stack grants layer read or write access on top of the role. Authorization
itself runs on Cedar, and the policies are in the repository under
`modules/persistence/src/main/resources/policies/` if you want to read the rules
directly.

Site administrators are separate again, and come from the
`STATEDB_ADMIN_SUBJECTS` allow-list rather than from any organization role.

## Sign in from the CLI

```sh
pulumi login https://statedb.example.com/pulumi
```

The CLI opens statedb in your browser, you authenticate with your identity
provider, and the token comes back to the CLI automatically. Tokens minted this
way last 24 hours and carry exactly the access of the person who signed in.

For CI, use a service account token instead and skip the browser entirely:

```sh
export PULUMI_ACCESS_TOKEN='<token>'
pulumi login https://statedb.example.com/pulumi
```

Setting `PULUMI_CONSOLE_DOMAIN` to your statedb host additionally lets the CLI
print console links after previews and updates.

## OpenID Connect

statedb works with any compliant OpenID Connect provider, such as Microsoft
Entra ID, Okta, Keycloak, or Google. Scopes must include `openid`.

Providers configured in the console are stored in PostgreSQL and can be added or
rotated without restarting the service. A provider seeded from the environment
during installation is the exception: it is re-applied at every start and shows
as read-only in the console. See
[configuring the first provider](/docs/deployment/#first-identity-provider).

## Service accounts

A service account is a machine identity independent of the person who created
it. When that person leaves the organization, CI keeps working.

Each account is created together with exactly one token, valid for 30, 90, or
365 days. There is no way to add a second token to an existing account, so
rotation means creating the replacement account, moving CI to it, and revoking
the old one. Give each workload its own account, so revoking one never
interrupts another.

Revocation is immediate, and it works in two directions: revoking the account
stops the token, and removing the account's organization membership stops it
from authenticating at all.

## Recovery access

The local recovery login is off unless you set
`STATEDB_LOCAL_GLASS_PASSWORD_HASH` to a bcrypt hash. It exists for one case:
your identity provider is unreachable and you need to get back in.

It is restricted:

- Loopback connections only, and `X-Forwarded-For` is not trusted.
- It disables itself whenever an enabled OIDC provider exists.
- It reuses the existing bootstrap administrator identity rather than creating
  a new one.

That OIDC condition is checked at sign-in and on every session restore, so
restoring your identity provider also ends any recovery session already open.

:::caution
`STATEDB_LOCAL_GLASS_ALLOW_REMOTE=true` lifts the loopback restriction and
exposes a password-authenticated admin login to the network. Use it only in an isolated evaluation environment.
:::
