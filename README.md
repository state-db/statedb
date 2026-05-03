# state-db

A self-hosted state management backend for Pulumi and OpenTofu. Implements the HTTP state storage protocol and pairs it with a web console for browsing stacks and resources — without requiring a managed cloud service.

End users configure their CLI to point at this service via `PULUMI_BACKEND_URL`. Existing workflows remain unchanged.

## License

This project is licensed under the [Elastic License 2.0](LICENSE).

You are free to use, modify, and self-host this software for your own internal business purposes at no cost. You may **not** offer it to third parties as a hosted or managed service. See the [LICENSE](LICENSE) file for full terms.
