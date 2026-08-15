---
title: License & pricing
description: Elastic License 2.0, free for up to ten active stacks per deployment.
---

statedb is source-available under the
[Elastic License 2.0](https://github.com/state-db/state-db/blob/main/LICENSE)
with an additional Free Edition Use Limit.

## The short version

- **Free** — use, modify, and self-host statedb for your own internal business
  purposes, free of charge, for up to **ten active stacks per deployment**.
- **Commercial** — beyond ten active stacks, a commercial license is required.
  See
  [COMMERCIAL.md](https://github.com/state-db/state-db/blob/main/COMMERCIAL.md)
  for terms and contact.
- **One hard restriction** — you may not offer statedb to third parties as a
  hosted or managed service. Running it for your own company (including for
  internal teams and subsidiaries) is exactly what it's for; reselling it as a
  service is not.

## Frequently asked

**What counts as an active stack?**
A stack that currently exists in the deployment's database. Deleted stacks
don't count. Separate deployments (separate databases) count separately. The
LICENSE file's definition is authoritative.

**Is the source really available?**
Yes — the full source is on
[GitHub](https://github.com/state-db/state-db). You can read every line that
touches your state, build it yourself, and audit the test suites the
[compatibility claims](/docs/compatibility/) rest on.

**What happens if I need more than ten stacks?**
Get in touch via
[COMMERCIAL.md](https://github.com/state-db/state-db/blob/main/COMMERCIAL.md) —
commercial licensing is how the project is funded. Deleting stacks you no
longer use also frees up room under the free limit.

**Can I contribute?**
Yes. Pull requests are accepted from contributors who have signed the
[CLA](https://github.com/state-db/state-db/blob/main/CLA.md); see
[CONTRIBUTING.md](https://github.com/state-db/state-db/blob/main/CONTRIBUTING.md).

## Offline commercial licenses

A commercial license is a local Ed25519-signed document. statedb never contacts
a licensing server. Missing, unreadable, invalid, not-yet-valid, and expired
licenses all resolve to the Community entitlement and do not prevent startup.

Mount the issued artifact and set:

```sh
STATE_DB_LICENSE_PATH=/run/secrets/state-db/license.json
```

`STATE_DB_LICENSE` may instead contain the compact envelope JSON or standard
base64 encoding of that whole JSON document. The mounted file takes precedence.
The file is read and verified on every enforced operation, so atomically
replacing it restores entitlement without restarting. `STATE_DB_DEPLOYMENT_ID`
checks a certificate's `deploymentId`. A certificate containing that claim is
invalid when the setting is absent or different. statedb does not fingerprint
machines or containers. License artifacts are limited to 64 KiB.

The admin endpoint `GET /api/admin/entitlement` reports the effective tier,
validation status, authoritative stack count, limit, paid expiration, and a
safe validation failure. It never returns customer/license identifiers,
commercial metadata, payloads, or signatures; expiration is intentionally
reported as effective-status data.

### Signed-envelope format

The outer document has exactly two strings:

```json
{"payload":"BASE64URL_WITHOUT_PADDING","signature":"BASE64URL_WITHOUT_PADDING"}
```

`payload` decodes to the exact UTF-8 bytes of the claims JSON. `signature` is
Ed25519 over those exact bytes. Verification never parses and reserializes the
payload. Both envelope and claims reject duplicate fields, unknown fields, and
trailing content. Schema 1 requires `schema`, `keyId`, `licenseId`,
`customerId`, `product`, `edition`, `issuedAt`, `notBefore`, and `expiresAt`;
`subscriptionId` and `deploymentId` are optional. `product` must be `state-db`
and `edition` must be `paid`. `notBefore` is inclusive and `expiresAt` is
exclusive.

Monthly and annual fulfillment use this same format and runtime path. Only the
signed timestamps differ; billing cadence is not an application concept.

### Vendor-side issuance

The website and marketplace fulfillment systems should share one issuer:

1. Keep each Ed25519 private key in a KMS/HSM or an access-controlled secret;
   never copy it into the statedb repository or image.
2. Build the schema-1 claims JSON in a fixed field order with RFC 3339 UTC
   timestamps and compact UTF-8 encoding.
3. Sign those exact bytes, base64url-encode payload and signature without
   padding, and emit the envelope above.
4. Retain old public keys in statedb while any certificate bearing their
   `keyId` remains valid. Add a new public key and key ID before issuing with a
   rotated private key.

The application currently contains a visibly marked placeholder for key ID
`2026-01`. Its private half is not known. Release engineering must replace the
placeholder with the public half held by the production issuer before
commercial issuance; this is a release blocker, not an issuable trust anchor.
The private key under test resources is explicitly test-only, uses a different
key ID and public key, and matches only the test verifier configuration.
