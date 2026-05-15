# Security Policy

## Reporting a vulnerability

**Please do not open a public GitHub issue for security reports.**

Use **GitHub's private vulnerability reporting**: open the repository's *Security* tab and click *Report a vulnerability*. This routes the report privately to the maintainer.

If you cannot use GitHub's private reporting for some reason, open a stub public issue saying "I would like to report a security issue privately" — do not include vulnerability details — and the maintainer will follow up with a private channel.

## What to include

Where possible:

- A description of the vulnerability and its likely impact.
- Reproduction steps, or a proof-of-concept.
- The version or commit SHA where the issue was observed.
- Whether the issue has been disclosed anywhere else.

## Response

state-db is a small project. The maintainer aims to acknowledge a report within **fourteen (14) days** of receipt and to provide a status update within thirty (30) days.

No formal service level agreement is offered. Disclosure timing will be coordinated with the reporter.

## Scope

**In scope:** the current `main` branch, and any tagged release made within the last 12 months.

**Out of scope:**

- Vulnerabilities in third-party dependencies — please report those directly to the upstream maintainer. If a dependency CVE materially affects state-db, mention that in the report so the maintainer can prioritize an upgrade.
- Misconfiguration of a self-hosted deployment (weak admin passwords, exposed DB, etc.).
- Denial-of-service that requires direct network access to a customer's own database.

## A note on what state-db stores

state-db stores Pulumi (and eventually OpenTofu) checkpoint blobs. Those checkpoints **can contain secrets** that operators of the upstream infrastructure chose to encode in state (cloud credentials, passwords, tokens, etc.). Vulnerabilities that expose or leak stored checkpoints, lock tokens, or API tokens are considered high severity.

Please coordinate disclosure carefully — a public proof-of-concept that exfiltrates a real checkpoint is not appropriate.

## Recognition

Reporters will be credited by name in release notes, unless they prefer to remain anonymous.
