---
title: License and Pulumi compatibility
description: statedb's license terms, and the basis for its Pulumi CLI compatibility.
---

statedb is licensed under the Elastic License 2.0 with one additional term, the
Free Edition Use Limit. The repository's
[LICENSE](https://github.com/state-db/statedb/blob/main/LICENSE) file is
authoritative; this page explains it in plain language and is not legal advice.

## Free Edition

One deployment may manage up to ten active stacks at no charge.

Each part of that sentence is defined. A stack is *active* if it currently
exists in statedb's data store. Deleted stacks stop counting, and users,
resources, projects, and update history never affect the number. A *deployment*
is one installation backed by one database, so independent deployments count
their stacks independently.

## Commercial use

Managing more than ten active stacks in a single deployment requires a
commercial license. Terms and pricing are not final; see
[COMMERCIAL.md](https://github.com/state-db/statedb/blob/main/COMMERCIAL.md) for
current status and how to get in touch.

Two limits apply regardless of edition. You may not offer statedb to third
parties as a hosted or managed service, and you may not modify it to disable or
circumvent license enforcement.

Otherwise the license is permissive: use, copy, distribute, and modify statedb
freely, provided modified copies carry a clear notice that they have been
changed.

## Why statedb can work with the Pulumi CLI

Pulumi separates the CLI from the state backend by design. Its own documentation
describes a backend as an API and storage endpoint the user chooses, and the CLI
ships with support for both Pulumi Cloud and self-managed backends.

statedb implements the HTTP protocol the unmodified CLI already speaks. It is
not Pulumi Cloud and does not claim to be.

The Pulumi CLI and engine are Apache 2.0 licensed. statedb uses public Pulumi
source and API descriptions to build its compatibility tests, and keeps the
Apache license and copyright notices for that material. The record is public:

- [Pulumi state and backend documentation](https://www.pulumi.com/docs/iac/concepts/state-and-backends/)
- [Pulumi source and Apache 2.0 license](https://github.com/pulumi/pulumi)
- [Pulumi-derived material in statedb](https://github.com/state-db/statedb/tree/main/third_party/pulumi)
- [state-db third-party notices](https://github.com/state-db/statedb/blob/main/NOTICE)

Compatibility is not endorsement. statedb is an independent project, and Pulumi
Corporation does not sponsor, endorse, or operate it.

## Trademarks

Pulumi is a trademark of Pulumi Corporation. This site uses the name only to
identify compatibility with the Pulumi CLI, and does not use Pulumi's logo as
its own.

Pulumi publishes [trademark rules](https://www.pulumi.com/trademark/) that
prohibit product names implying endorsement. The statedb name and visual design
are deliberately separate from Pulumi branding.

## Source availability

The full source is public on
[GitHub](https://github.com/state-db/statedb), including the state path,
database migrations, access policies, console, and compatibility tests.

Source-available is not open source. statedb carries the limits described above,
and its third-party components keep their own licenses.
