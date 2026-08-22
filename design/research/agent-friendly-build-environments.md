# Agent-friendly build environments

Research date: 2026-08-14

## Question

How should state-db provide reproducible, portable, supply-chain-conscious local development and CI without making a persistent dev container the execution gateway for humans, coding agents, and GitHub Actions?

## Findings

Agent-oriented repositories generally expose repository-owned task commands and treat environment implementations as adapters. OpenAI Codex uses `just` commands as its contributor interface while also carrying Nix and dev-container definitions. Convex uses mise plus Just for the same local/CI toolchain. OpenCode provides a cross-platform Nix shell. The common property is not one environment technology: agents receive small, intent-named commands instead of editor-container lifecycle instructions.

GitHub-hosted runners are fresh virtual machines, whereas self-hosted runners can retain compromise between jobs. A GitHub-hosted release job therefore does not need a dev container merely to obtain job isolation. A dev container is also not a meaningful malware boundary when it receives the host Docker socket: Docker documents that daemon control can mount and modify the host filesystem without restriction.

Toolchain pinning does not require an operating-system container. Mise can pin platform-specific tools and checksums and expose a task dependency graph. Its lightweight filesystem/network sandbox is useful defence in depth, but it is not documented as a comprehensive macOS Keychain, credential-helper, Mach-service, or Docker-daemon boundary. Maven Wrapper pins Maven independently and supports SHA-256 verification of its distribution. Bun blocks arbitrary dependency lifecycle scripts by default except trusted packages, supports `--ignore-scripts`, and supports frozen lockfile installs.

Containers remain useful when the container is the product or the actual test dependency: PostgreSQL, Pulumi CLI compatibility images, Mandrel/native-image builds, scanners, and release artifacts. Quarkus Dev Services and Testcontainers are designed to talk directly to a supported Docker environment; wrapping the JVM itself in a dev container creates nested networking and daemon-discovery complexity without improving that contract.

## Alternatives

### 1. Container-per-task

Build every task in a short-lived pinned image. This gives a consistent Linux userspace and a simple CI mapping, but remains expensive for tight agent loops, complicates Testcontainers, hides native caches, and still becomes unsafe if the task gets the host Docker socket.

### 2. Hermetic build platform (Nix, Bazel, or Dagger)

This offers the strongest reproducibility or graph model. It also introduces a second build description beside Maven, Bun, and Go, and Dagger retains an OCI engine. The migration and debugging cost is disproportionate for this repository today.

### 3. Repository-owned workspace kernel with a credential-free VM (recommended)

Expose one repository command (`./dev`) backed by pinned tools and a task DAG. Copy Git-visible source into a persistent Lima VM created with no host mounts. Run Java, Bun, Go, Maven plugins, and Testcontainers there with a guest-only home and rootless Docker daemon. The VM preserves caches between iterative runs but receives no host credential directories, SSH agent, GitHub token, cloud configuration, or host Docker socket. A guest firewall blocks new connections to Lima's host gateway, and repository code has no sudo access with which to remove it. Commit signoff starts from a freshly created VM rather than trusting persistent guest state.

This design has the deepest interface: callers name intent while tool installation, Maven reactor flags, caches, service topology, worktree isolation, and platform differences stay hidden behind one module.

## Final decision

The repository initially retained the dev container as an optional editor adapter. Once `./dev`
owned verification and the host-native workbench owned interactive development, that adapter had
no unique supported responsibility. Its separately pinned toolchain drifted, Docker socket access
provided no security boundary, and its linked-worktree lifecycle added substantial operational
complexity. The dev-container definition and lifecycle were therefore removed; purpose-specific
containers remain where the container is the product or a real test dependency.

## Sources

- [Codex contributor commands](https://github.com/openai/codex/blob/main/codex-rs/AGENTS.md)
- [Convex mise configuration](https://github.com/get-convex/convex-backend/blob/main/mise.toml)
- [OpenCode Nix development shell](https://github.com/anomalyco/opencode/blob/dev/flake.nix)
- [GitHub secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [Docker daemon security](https://docs.docker.com/engine/security/)
- [Development Containers overview](https://containers.dev/)
- [Mise task architecture](https://mise.jdx.dev/tasks/architecture.html)
- [Mise lockfiles](https://mise.jdx.dev/dev-tools/mise-lock.html)
- [Mise task sandboxing](https://mise.jdx.dev/tasks/sandbox.html)
- [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/index.html)
- [Bun dependency lifecycle scripts](https://bun.sh/docs/pm/lifecycle)
- [Testcontainers supported Docker environments](https://java.testcontainers.org/supported_docker_environment/)
- [Quarkus Dev Services](https://quarkus.io/guides/dev-services)
- [Docker Compose project names](https://docs.docker.com/compose/how-tos/project-name/)
- [GitHub manual workflow dispatch](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)
