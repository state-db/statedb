# workbench

Local, non-hermetic environments for driving a real state-db instance by hand.
None of this is exercised by `./dev check` or CI — it's for interactive use.

- **[`pulumi/`](pulumi/README.md)** — the day-to-day dev workbench: Postgres +
  mock IdP + the app (built image by default, or hot-reload dev mode), all
  host-native via `pulumi/dev-host.sh` — no dev container. Plus a
  fixture-estate generator, churn runs, and a high-cardinality scale simulator
  (`pulumi/providers/statedbsim`). Owns most of the interesting logic here.
- **[`soak/`](soak/README.md)** — a separate, long-running endurance harness
  that drives the real container image under continuous Pulumi load for days
  to catch heap/RSS/connection-pool drift. Runs its own Compose stack from the
  host.

Both are reached through the root `Makefile` (`make pulumi-workbench cmd=...`,
`make soak cmd=...`), which just forwards to `make -C workbench/<dir> <cmd>`.

`.pulumi-home/` and `.generated/` are local scratch state, gitignored and
safe to delete (`make pulumi-workbench cmd=reset` recreates them).
