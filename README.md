<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/brand/statedb-logo-dark.svg">
    <img src="assets/brand/statedb-logo.svg" alt="statedb — self-hosted state backend" width="460">
  </picture>
</div>

<img src="assets/brand/statedb-mascot.png" alt="The state-db otter mascot" align="right" width="130">

A self-hosted state management backend compatible with the Pulumi and OpenTofu command-line tools. It implements HTTP state storage protocols and pairs them with a web console for browsing stacks and resources — without requiring a managed cloud service.

End users configure their CLI to point at this service via `PULUMI_BACKEND_URL`. Existing workflows remain unchanged.

Third-party attributions and trademark notices are in [NOTICE](NOTICE).

## How we prove your state is safe

State backends earn trust through their failure modes, not their happy paths. state-db is tested in tiers:

- **Contract replay** — every HTTP exchange from real recorded Pulumi CLI sessions (`up`, `preview`, `refresh`, `destroy`, tags, rename, export/import, …) is replayed against the server and must reproduce byte-for-byte modulo documented volatile fields.
- **Integrity verification** — every deployment the server assembles in tests is checked with Pulumi's own `snapshot.VerifyIntegrity` (via [`tools/pulumi/verify`](tools/pulumi/verify)), so stored state is state the CLI itself would agree to load.
- **CLI smoke** — the real Pulumi CLI, in Docker, driving the backend per command family.
- **Fault injection** — adversarial scenarios with the real CLI: killed mid-update, concurrent updates, failed deployments, corrupted imports, compaction and secrets durability. Each run emits a [State Safety Report](modules/pulumi-cli-smoke/README.md) listing every scenario and its outcome; the nightly CI matrix repeats it across supported Pulumi CLI versions.

See [`modules/pulumi-cli-smoke/README.md`](modules/pulumi-cli-smoke/README.md) for running the slow tiers locally and [`design/pulumi-state-testing-roadmap.md`](design/pulumi-state-testing-roadmap.md) for the full scenario catalog.

## License

This project is licensed under the [Elastic License 2.0, with Additional Limitations](LICENSE).

You are free to use, modify, and self-host this software for your own internal business purposes, free of charge, for up to **ten (10) active stacks per deployment**. Beyond that, a commercial license is required — see [COMMERCIAL.md](COMMERCIAL.md). You may **not** offer this software to third parties as a hosted or managed service. See the [LICENSE](LICENSE) file for full terms.

## Contributing

Contributions are welcome. Pull requests are accepted only from contributors who have signed the [Contributor License Agreement](CLA.md); see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow.
