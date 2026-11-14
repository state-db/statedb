# Service secrets

state-db implements Pulumi's `service` secrets provider. Stacks can instead use
an external provider such as a passphrase or a cloud KMS; those encrypt on the
client, and state-db stores their ciphertext unchanged.

For service-managed secrets, the backend sees plaintext only while handling an
encrypt or decrypt request. Checkpoints and journal entries hold ciphertext, and
the console never decrypts.

## Key storage

`ServiceSecretsKeysetSeeder` generates a Google Tink AES-256-GCM keyset on first
startup and stores it in the singleton `service_secrets_keyset` row. A named
PostgreSQL advisory lock serializes concurrent startups, and an existing row is
never changed. Key generation runs in application code, so the key material
never appears in Flyway history, environment variables, or shell history.
`ServiceSecretsAead` loads the keyset on first use and caches the AEAD primitive
in the process.

The keyset is not wrapped by an external KMS, so a full database copy contains
both the ciphertext and the key that decrypts it. What the service provider
protects against is exposure short of that: a checkpoint export, a journal dump,
event payloads in logs, or a backup of some tables without the keyset row.
Anyone with the whole database, or with a running process's memory, can read
every service-managed secret. Deployments whose threat model includes full
database compromise should use an external secrets provider, where state-db
holds only ciphertext it cannot decrypt.

A restore without the original keyset row cannot recover existing secrets.
Startup seeds a new keyset if the row is missing, but that keyset cannot decrypt
old ciphertext. The lazy loader fails if the row is missing at request time
rather than generating a key outside the locked startup path.

## Stack isolation

All stacks share the keyset. Each operation passes the stack's 16-byte UUID as
authenticated associated data, so ciphertext from one stack fails to decrypt
under another stack's UUID. The UUID survives a rename, so existing ciphertext
stays valid; deleting and recreating a stack with the same name produces a new
UUID that old ciphertext cannot match. The service URL is not part of the
binding, so DNS or proxy changes never orphan secrets.

Tink owns key selection, nonce generation, and ciphertext framing. The API
standard-base64-encodes those bytes without adding a state-db envelope.

## Endpoints

All routes use this prefix:

```text
/pulumi/api/stacks/{org}/{project}/{stack}
```

| Method and suffix | Request | Response |
| --- | --- | --- |
| `POST /encrypt` | `{"plaintext":"text"}` | `{"ciphertext":"base64"}` |
| `POST /decrypt` | `{"ciphertext":"base64"}` | `{"plaintext":"text"}` |
| `POST /batch-decrypt` | `{"ciphertexts":["base64"]}` | `{"plaintexts":{"base64":"text"}}` |

These routes use API-token authentication and stack authorization; update leases
play no part. Encryption requires `stack:EncryptSecrets`. Decryption requires
`stack:DecryptSecrets`, which is part of the read bundle, so anyone who can read
a stack can decrypt its service-managed secrets.

Malformed or unauthentic ciphertext returns 400 with a generic
`invalid ciphertext` message that does not say which check failed, and one
invalid item fails the whole batch. The batch response maps each input
ciphertext to its plaintext, so duplicates produce one entry. Encrypting the
same plaintext twice yields different ciphertext.

## Other service credentials

The GitHub integration encrypts its App private key and OAuth client secret with
the same keyset. Their associated data has the form
`vcs-app:<host>[:oauth]:<version>`, which cannot match a stack UUID or each
other, so no ciphertext decrypts in another context.

OIDC provider client secrets are stored in their provider rows without keyset
encryption. Protecting the database as a whole remains a deployment
responsibility.

## Limits and verification

There is no key-rotation or KMS-wrapping workflow. Changing the keyset row
directly also leaves running processes with cached key material.

The [secrets resource](../modules/pulumi-api/src/main/java/app/statedb/pulumiapi/secrets/SecretsResource.java)
owns wire behavior, and the [keyset services](../modules/persistence/src/main/java/app/statedb/persistence/secrets/)
own generation and loading. The real-CLI fault suite includes
[secrets durability](../modules/pulumi-cli-smoke/src/test/java/app/statedb/pulumiclismoke/faults/SecretsDurabilityFaultIT.java).
