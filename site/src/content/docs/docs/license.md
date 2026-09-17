---
title: License and Pulumi compatibility
description: state-db's open-source license and the basis for its Pulumi CLI compatibility.
---

state-db is open-source software under the GNU Affero General Public License v3.0 only. The SPDX identifier is `AGPL-3.0-only`.

The repository [LICENSE](https://github.com/state-db/statedb/blob/main/LICENSE) contains the complete license text. That file is authoritative.

## Source code for network users

The AGPL includes a source-code requirement for modified software that users access over a network. Review section 13 of the license for the exact requirement.

The complete source for the official state-db service is available in the [state-db repository](https://github.com/state-db/statedb). This source includes the server, console, database migrations, access policies, and compatibility tests.

If you operate a modified version, give its network users a clear link to the corresponding source. The source must match the version that those users access.

This page summarizes the license. The [LICENSE](https://github.com/state-db/statedb/blob/main/LICENSE) text controls if this summary differs from it.

## Why state-db can work with the Pulumi CLI

Pulumi separates the CLI from the state backend. Its documentation describes a backend as an API and storage endpoint that a user selects.

state-db implements the HTTP protocol that the unmodified CLI uses. state-db is not Pulumi Cloud and does not claim to be.

The Pulumi CLI and engine use the Apache License 2.0. state-db uses public Pulumi source and API descriptions for compatibility tests.

The repository preserves the required notices:

- [Pulumi state and backend documentation](https://www.pulumi.com/docs/iac/concepts/state-and-backends/)
- [Pulumi source and Apache 2.0 license](https://github.com/pulumi/pulumi)
- [Pulumi-derived material in state-db](https://github.com/state-db/statedb/tree/main/third_party/pulumi)
- [state-db third-party notices](https://github.com/state-db/statedb/blob/main/NOTICE)

Compatibility is not endorsement. state-db is an independent project. Pulumi Corporation does not sponsor, endorse, or operate it.

## Trademarks

Pulumi is a trademark of Pulumi Corporation. This site uses the name only to identify compatibility with the Pulumi CLI.

Pulumi publishes [trademark rules](https://www.pulumi.com/trademark/). The state-db name and visual design are separate from Pulumi branding.
