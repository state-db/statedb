# state-db

A self-hosted state management backend for Pulumi and OpenTofu. Implements the HTTP state storage protocol and pairs it with a web console for browsing stacks and resources — without requiring a managed cloud service.

End users configure their CLI to point at this service via `PULUMI_BACKEND_URL`. Existing workflows remain unchanged.

## License

This project is licensed under the [Elastic License 2.0, with Additional Limitations](LICENSE).

You are free to use, modify, and self-host this software for your own internal business purposes, free of charge, for up to **ten (10) active stacks per deployment**. Beyond that, a commercial license is required — see [COMMERCIAL.md](COMMERCIAL.md). You may **not** offer this software to third parties as a hosted or managed service. See the [LICENSE](LICENSE) file for full terms.

## Contributing

Contributions are welcome. Pull requests are accepted only from contributors who have signed the [Contributor License Agreement](CLA.md); see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow.
