---
title: Authentication
description: CLI login, OIDC single sign-on, service accounts, and break-glass recovery.
---

statedb has two audiences to authenticate: the CLI talking to the backend, and
humans using the web console.

## CLI login

`pulumi login <your-statedb-url>` uses the CLI's native browser flow: the CLI
starts a loopback listener, opens your browser at the statedb console, and once
you're signed in the console mints a token and hands it back to the CLI. No
manual token copying.

For CI and other non-interactive use, set `PULUMI_ACCESS_TOKEN` with a service
account token. The backend authenticates every API request via the standard
`Authorization: token <token>` header.

## Console sign-in: OIDC

Console authentication is OpenID Connect. Configure one or more providers —
Okta, Microsoft Entra, Keycloak, Google, or any compliant IdP — and users sign
in with the identity they already have. Providers are stored as data, so adding
or rotating one does not require a redeploy.

## Service accounts

Service accounts hold long-lived tokens for automation. Create them in the
console, use their tokens in CI via `PULUMI_ACCESS_TOKEN`, and revoke them from
the console when they should stop working.

## Break-glass recovery

If your IdP is down, you can still reach the console through a deliberately
constrained local login:

- **Opt-in** — set `STATE_DB_LOCAL_GLASS_PASSWORD_HASH` to a bcrypt hash
  (modular crypt format). It lives in the environment, never the database, so
  recovery credentials stay readable when the database of record is the thing
  you're recovering.
- **Loopback-only** — login is accepted only from the machine itself
  (`X-Forwarded-For` is never trusted). Set
  `STATE_DB_LOCAL_GLASS_ALLOW_REMOTE=true` only for isolated evaluation
  setups.
- **Self-disabling** — break-glass refuses to operate while an enabled OIDC
  provider exists, checked at login *and* on every session restore. Existing
  break-glass sessions die the moment OIDC is back. Re-opening it during an
  IdP outage is a deliberate act, not a forgotten flag.

A successful break-glass login authenticates as the bootstrap admin identity —
it cannot escalate beyond what that identity already has.
