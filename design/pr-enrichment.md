# GitHub PR reports

GitHub enrichment is an optional add-on. An unmodified Pulumi CLI supplies commit metadata and repository tags.
A completed run produces a compact report with a colored diff, risky changes first, honest truncation, and a full-preview link.
The existing renderer owns report content and secret redaction.

## Ownership and storage

The integration has four tables in one unshipped migration, V003:

- `vcs_app_registrations` stores the GitHub App private key and OAuth client secret, encrypted with `service_secrets_keyset`, and the App's public identity.
- `vcs_auth_flows` stores expiring, one-time registration and connection state bound to the initiating user and organization.
- `vcs_connections` binds an installation to an organization. Organization owners manage their own connections.
- `report_deliveries` stores one report per run, including captured metadata, frozen report content, retries, and the result.

The migration creates only add-on objects. It has no foreign key to `stack_updates` and does not alter, scan, or backfill update history.
The existing update entity is unchanged. Registration, encrypted credentials, and Cedar authorization retain their existing ownership.

`ReportDeliveryWorker` owns enqueue, polling, retries, and retention. `GitHubPublisher` owns target verification and comment reconciliation.
`ReportPublisher` is the small interface between persistence and GitHub HTTP code. No publication-head table, terminal sweeper, or separate metadata table remains.
Commit statuses and start-phase publication are removed. This integration publishes completed reports only.

## Connecting an organization

A site administrator creates the deployment-owned GitHub App through its manifest flow.
The manifest registers installation and OAuth callbacks under `statedb.reports.public-base-url`.
No request header or callback establishes the public host. The same configured URL supplies report links.

An organization owner selects **Connect GitHub**:

1. State-db creates a 15-minute, one-use installation state bound to the user, organization, and App version.
2. GitHub lets the user select an account and repository access, then returns an installation identifier.
3. State-db consumes the installation state and starts a separate OAuth handshake with fresh state and PKCE.
4. The server exchanges the authorization code and verifies that `/user/installations` contains the returned installation for this App.
5. State-db checks installation suspension and pull-request write permission, then connects the original organization automatically.

The installation identifier is only a hint. App-level lookup alone never authorizes connection.
Fixed callback endpoints route to organization-scoped endpoints, which recheck the current Cedar `vcs:Manage` permission.
The callback cannot select a different organization. Replayed, expired, wrong-user, and wrong-purpose states fail.
App replacement invalidates existing handshakes, including a version check when the connection is committed.
OAuth user tokens remain in server memory for this verification only. They are not stored, returned to the browser, or used for report delivery.
Private keys and OAuth client secrets use different authenticated-encryption contexts.

The connection card shows the verified GitHub account and whether repository access covers all or selected repositories.
**Manage repositories** opens GitHub's installation settings. A return without state does not create or change a connection.
**Disconnect** disables the local connection and removes its pending authorization handshakes. It does not uninstall the App on GitHub.
Already-running publication or authorization requests can finish. Reports processed after disconnection fail without publication.
Connection verification failures retain the previous connection and present a retry action.
Pending installation approval and denied authorization have explicit return messages.

The global installation picker and direct installation-ID connection endpoint are removed.
GitHub documents [setup-ID verification](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url)
and the [user authorization and PKCE flow](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app).

## Repository scope

State-db has no per-repository binding. Any stack writer in a connected organization can have a report posted to any repository the installation can access, by setting the `vcs:owner` and `vcs:repo` tags.
The installation's repository access on GitHub is the only boundary. Organization owners narrow it with **Manage repositories**, so state-db keeps no repository list of its own.
If a skip list is ever needed, it belongs on the connection as `owner/repo` patterns checked at enqueue. It is not built.

## Queue lifecycle

The transaction that ends a run calls `enqueue`: `/complete`, graceful cancel, forced cancel, and forced unlock. It reads the tags Pulumi refreshed at lease time from the update row.
Runs abandoned by an expired lease are not reported.
An enabled organization connection and a valid commit are required; otherwise no queue row is written.
Enqueue is one `INSERT ... SELECT ... ON CONFLICT (update_id) DO NOTHING`, so it cannot fail the run's commit on a missing connection or a repeated call. A database identity on the queue orders runs by enqueue order.

The worker selects at most 50 due rows through a partial `(next_attempt_at, id)` index.
For each row, it reads the update by its existing primary key.
Historical updates never enter the queue automatically.

Before publication, the worker verifies one open PR with the exact commit. Ambiguous, closed, or mismatched targets fail without posting.
It commits the verified repository, PR number, and rendered body before remote writes. Retries use this frozen content even after a force-push or closure.

Rows have three states: `pending`, `delivered`, and `failed`. Preparation is represented by the frozen body, without a separate state machine.
Five attempts apply separately to preparation and publication. Transient failures use exponential backoff and respect GitHub rate-limit delays.
Definitive HTTP failures and GraphQL permission or validation errors stop immediately. Pending work expires after seven days by default.

## Concurrency and recovery

`FOR UPDATE SKIP LOCKED` prevents two workers from processing the same queue row.
A transaction-scoped advisory lock serializes publication for a stack across replicas. It does not lock Pulumi state or update rows.
Database rollback and connection cleanup release both locks. There is no durable claim or lease to repair.
A publication transaction has a 90-second timeout. HTTP requests have 20-second timeouts.
The publisher stops starting requests after its 40-second reconciliation budget expires.

Each comment starts with `<!-- statedb-report:STACK:GENERATION:UPDATE -->`.
Reconciliation accepts markers only from the registered App's bot account on the verified PR.
It reads the complete comment list before posting, with a limit of 20 pages of 100 comments.
An incomplete search fails without creating a comment. The limit is an explicit terminal failure, not evidence that no comment exists.

If the run marker exists, the publisher reuses that comment without changing its body.
Otherwise, it creates a new comment. Only after that comment exists does it minimize preceding reports with `OUTDATED`.
The highest generation stays current. A delayed older run becomes collapsed history immediately.
Duplicate markers use a stable node-ID tie-breaker, leaving one current comment. Reports from other stacks or authors remain unchanged.

Crash recovery uses the same procedure:

1. Before posting, a crash leaves pending work and its frozen report.
2. After posting but before recording the ID, a retry finds the marker and reuses the comment.
3. During minimization, a retry skips already minimized comments and completes the remaining work.
4. After remote success but before database commit, a retry repeats reconciliation and records the same result.

GitHub provides no transaction shared with PostgreSQL or idempotency key for comment creation.
A lost response can temporarily leave two visible reports. Recovery converges after GitHub reads expose the completed writes.
A permanent permission failure or exhausted retry budget requires operator action. The worker never reports that partial cleanup succeeded.
Human deletion of a marker comment can cause recreation during a retry. Human minimization is respected.
Changing the registered App identity does not authorize the new bot to claim the previous bot's comments.

## GitHub API and permissions

The App requests `pull_requests: write`; commit-status permission is no longer requested.
[GitHub REST documentation](https://docs.github.com/en/rest/issues/comments#create-an-issue-comment) explicitly permits installation tokens with this permission to create PR comments.
[GitHub GraphQL documentation](https://docs.github.com/en/graphql/reference/issues#minimizecomment) defines `minimizeComment`, `subjectId`, and the minimization result.
The comment's `viewerCanMinimize` field provides a runtime permission check before another report is posted.
[The MCP servers workflow](https://github.com/modelcontextprotocol/servers/blob/main/.github/workflows/readme-pr-check.yml) demonstrates minimization with only `pull-requests: write`.
GitHub does not publish a separate mutation-to-App-permission matrix. The runtime capability check is authoritative for each comment.
No comment body is updated or deleted. The documented [OUTDATED classifier](https://docs.github.com/en/graphql/reference/meta#reportedcontentclassifiers) preserves the report as collapsed history.

## Operations

Set `statedb.reports.public-base-url` to the public application URL, including a deployment path prefix when applicable.
The URL must use HTTP or HTTPS and contain no credentials, query, or fragment.
Missing or invalid configuration fails the report with a clear reason. GitHub callbacks never establish the public host.

Inspect a report by its indexed update identifier:

```sql
SELECT id, state, attempts, next_attempt_at, target_pr_number, external_id, last_error
FROM report_deliveries WHERE update_id = :update_id;
```

Structured delivery logs include `delivery_id`, `update_id`, `stack_id`, and `pr_number`.
Micrometer exposes `statedb.reports.queue.depth`, `attempts`, `delivered`, `failures`, and `delivery.latency` under the same prefix.
Queue depth is sampled after each enabled tick and capped at 10,000 to bound query cost. A value of 10,000 means at least that many pending reports.
Counters measure observed attempts and outcomes, not an exactly-once accounting ledger across database rollbacks.

Settled rows expire after 30 days. Each tick deletes at most 50 rows through the partial `(settled_at, id)` index.
Retention also runs while publication is disabled. Disabled publication pauses existing pending rows and prevents new enqueue calls.
The queue-age limit applies when publication resumes. GitHub retains the comment history after local queue retention.
Handshake cleanup uses its expiry index when a registration or connection starts. Disconnect uses the organization index.

After correcting a terminal failure, retry its retained row:

```sql
UPDATE report_deliveries
SET state = 'pending', attempts = 0, settled_at = NULL, last_error = NULL,
    next_attempt_at = now()
WHERE id = :delivery_id AND state = 'failed' AND created_at > now() - interval '7 days';
```

The default seven-day predicate matches the queue-age limit. Use the configured limit if it differs.
Retained frozen content makes this retry safe. Do not recreate expired queue rows from historical updates.

## Verification scope

HTTP test sources cover immutable bodies, lost responses, duplicate markers, delayed runs, permissions, pagination limits, and error classification.
Database test sources cover terminal entry points, concurrent workers, frozen targets, retention, and terminal failures.
Authorization test sources also cover installation spoofing, callback replay, changed membership, disconnect, PKCE, and OAuth secret encryption.
Existing report-formatting and hostile-content tests remain.
This revision was inspected statically. Tests, builds, native checks, CLI smoke tests, and schema generation were skipped at the user's request.
The schema snapshot was updated manually and still requires comparison with `./dev schema` in a later verification pass.
