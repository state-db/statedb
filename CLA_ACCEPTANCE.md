# Contributor agreement acceptance

This repository uses an Individual Contributor License Agreement (ICLA) and a Corporate Contributor License Agreement (CCLA). It does not use a Developer Certificate of Origin.

## Acceptance record

The checked-in file `signatures/v1/cla.json` is the merge gate and durable acceptance record. Each record contains:

- The contributor's GitHub login, legal name, and contact email.
- Each covered Git author name and email.
- The agreement type, version, and exact SHA-256 hash.
- The acceptance timestamp and the pull request or issue that contains the acceptance statement.
- For a CCLA, the legal entity, authorized representative, and covered contributors.

A pull request checkbox is not acceptance by itself. Before merge, the contributor must post this statement from the GitHub account in the record:

> I accept the state-db ICLA-1.0 at SHA-256 `<hash>`. My covered Git identities are: `<name> <email>`. I understand that my acceptance is recorded in `signatures/v1/cla.json`.

An authorized corporate representative uses the same statement with `CCLA-1.0`. The statement must identify the legal entity and each covered contributor.

The maintainer checks the identity, timestamp, agreement hash, and covered Git identity against the pull request commits. Then the maintainer adds the record to `signatures/v1/cla.json` in the pull request or a linked pull request. The pull request cannot merge until that record is present.

The `Contributor agreement` workflow checks the pull request author and every Git author identity. It also checks each agreement file against its recorded SHA-256 hash. The named Steward and listed automation accounts do not accept a contributor agreement for their own work.

An individual acceptance record has this shape:

```json
{
  "agreement": "ICLA-1.0",
  "agreementSha256": "<sha256>",
  "githubLogin": "<login>",
  "legalName": "<name>",
  "contactEmail": "<email>",
  "acceptedAt": "<ISO-8601 timestamp>",
  "evidenceUrl": "<pull request or issue comment URL>",
  "gitIdentities": [{ "name": "<Git author name>", "email": "<Git author email>" }]
}
```

A corporate record uses `CCLA-1.0`. It also contains `legalEntity`, `authorizedRepresentative`, and `coveredContributors` fields.

If an agreement changes, create a new version and hash. Do not change the meaning of a version that has acceptance records.
