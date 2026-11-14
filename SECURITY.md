# Security Policy

## Reporting a vulnerability

Do not open a public GitHub issue for security reports.

Use GitHub's private vulnerability reporting: open the repository's *Security* tab and click *Report a vulnerability*. The report goes privately to the maintainer.

If you cannot use GitHub's private reporting, open a public issue that says only "I would like to report a security issue privately", with no vulnerability details. The maintainer will follow up through a private channel.

## What to include

Where possible:

- A description of the vulnerability and its likely impact.
- Reproduction steps, or a proof-of-concept.
- The version or commit SHA where the issue was observed.
- Whether the issue has been disclosed anywhere else.

## Response

The maintainer aims to acknowledge a report within 14 days of receipt and to provide a status update within 30 days.

No service level agreement is offered. Disclosure timing is coordinated with the reporter.

## Scope

In scope: the current `main` branch, and any tagged release made within the last 12 months.

Out of scope:

- Vulnerabilities in third-party dependencies — report those to the upstream maintainer. If a dependency CVE materially affects state-db, say so in the report so the maintainer can prioritize an upgrade.
- Misconfiguration of a self-hosted deployment (weak admin passwords, exposed DB, etc.).
- Denial-of-service that requires direct network access to the operator's own database.

## What state-db stores

state-db stores Pulumi checkpoints. Checkpoints can contain secrets that users chose to put in state, such as cloud credentials, passwords, and tokens. Vulnerabilities that expose stored checkpoints, lock tokens, or API tokens are high severity.

A public proof-of-concept that exfiltrates a real checkpoint is not appropriate.

## Recognition

Reporters will be credited by name in release notes, unless they prefer to remain anonymous.
