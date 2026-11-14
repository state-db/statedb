# GitHub PR reports

The optional GitHub integration posts a report on the pull request for a run's
commit after the run ends. It works with an unmodified Pulumi CLI, using the
commit metadata and repository tags the CLI already sends. It publishes
completed reports only, never commit statuses or progress for a running update.
The integration supports `github.com`.

## Ownership and storage

`ReportDeliveryWorker` owns enqueue, polling, preparation, retries, and
retention. `GitHubPublisher` verifies pull-request targets and reconciles
comments. `ReportPublisher` is the interface between persistence and the GitHub
HTTP code.

The [integration migration](../modules/persistence/src/main/resources/db/migration/V003__add_vcs_report_delivery.sql)
creates four tables:

| Table | Purpose |
| --- | --- |
| `vcs_app_registrations` | The App's public identity plus its encrypted private key and OAuth client secret |
| `vcs_auth_flows` | Expiring, one-use authorization state bound to a user and organization |
| `vcs_connections` | An organization's connection to an installation |
| `report_deliveries` | One row per run: captured metadata, frozen report, attempts, and outcome |

The private key and client secret are encrypted with the
[service secrets](secrets.md) keyset under separate associated data that also
binds the App version. `report_deliveries` has no foreign key to
`stack_updates`, so the queue never locks or scans update history, and its
retention has no effect on that history. Historical runs never enter the queue.

## Connecting an organization

A site administrator creates the deployment-owned GitHub App through GitHub's
manifest flow. The App requests `pull_requests: write` only.
`statedb.reports.public-base-url` supplies the public URL for the App's
callbacks and for console links in reports; request headers never establish
that host. The value must be an `http` or `https` URL without credentials, query,
or fragment, and an invalid value stops startup. While it is unset, GitHub setup
is disabled.

An organization owner then connects the organization:

1. state-db creates a 15-minute, one-use installation state bound to the user,
   organization, and App version.
2. On GitHub, the user picks an account and repository access, and GitHub
   returns an installation ID.
3. state-db consumes that state and starts a separate OAuth flow with fresh
   state and PKCE.
4. After exchanging the code, the server checks that the user's
   `/user/installations` list contains the returned installation for this App,
   that the installation is not suspended, and that it grants pull-request write.

The installation ID from the redirect is only a hint, because anyone can put one
in a URL; the OAuth check proves the connecting user can access it. Callbacks
recheck the initiating user, the organization, the App version, and current
`vcs:Manage` authority, so replayed, expired, or wrong-purpose state fails, and
replacing the App invalidates handshakes in progress. The OAuth user token stays
in memory for this verification and is never stored or used for delivery.

Disconnect disables the local connection and deletes its pending authorization
flows. It does not uninstall the App on GitHub. A request already in flight can
finish; later delivery attempts fail on the disabled connection.

## Repository scope

There is no per-stack repository binding. The CLI sets the `vcs:kind`,
`vcs:owner`, and `vcs:repo` stack tags from the Git remote, and any stack writer
can change them. A report can therefore target any repository the
organization's installation can access, and the installation's repository
access on GitHub is the only boundary. The connection view links to GitHub's
installation settings to narrow it; state-db keeps no repository allowlist of its
own.

## Queue lifecycle

The transaction that ends a run enqueues its report: completion, graceful or
forced cancellation, and forced unlock. A run abandoned through an expired lease
produces no report. Enqueue reads the tags refreshed when the update started and
needs all of the following, otherwise it writes nothing:

- reports enabled (`statedb.reports.enabled`)
- `vcs:kind`, `vcs:owner`, and `vcs:repo` tags, with `vcs:kind` matching an
  enabled connection's host for the stack's organization
- a commit SHA from `ci.pr.headSHA`, falling back to `git.head`, in the update's
  environment metadata

When the CLI also reports `ci.pr.number`, the target PR must have that number.
Pulumi fills the `ci.pr.*` keys for CI systems it recognizes, or from the
`PULUMI_CI_*` variables when `PULUMI_CI_SYSTEM` is set.

Enqueue is a single `INSERT ... SELECT ... ON CONFLICT (update_id) DO NOTHING`,
so a missing connection or a repeated call can never fail the run's commit. An
identity column records enqueue order as the report's generation. Rows are
`pending`, `delivered`, or `failed`.

Each delivery has two phases with separate attempt budgets. Preparation finds
one open PR whose head is exactly the run's commit; an ambiguous, closed, or
mismatched target fails without a comment. The worker then commits the verified
target and the rendered body before any remote write, and every retry posts that
frozen content even after a force-push or a closed PR. Publication posts it.

Defaults allow five attempts per phase and seven days of queue age. Transient
failures back off exponentially up to five minutes and honor GitHub rate-limit
delays. Definitive HTTP failures and GraphQL permission or validation errors stop
retrying immediately.

## Concurrent delivery and recovery

`FOR UPDATE SKIP LOCKED` keeps two workers off the same row. A
transaction-scoped advisory lock serializes publication per stack across
replicas without locking Pulumi state or update rows. Rollback releases both, so
no claim or lease needs repair after a crash. A publication transaction has a
90-second timeout, each HTTP request 20 seconds, and the publisher stops starting
requests after its 40-second reconciliation budget.

Each comment begins with this marker:

```text
<!-- statedb-report:STACK:GENERATION:UPDATE -->
```

The publisher trusts a marker only on a comment by the registered App's bot on
the verified PR. A replacement App's bot therefore cannot claim earlier reports.
Before posting, the publisher reads the complete comment list, up to 20 pages of
100 comments; a longer or incomplete list fails without posting, because a
partial search is not evidence that no report exists.

If a comment for this run exists, the publisher reuses it unchanged. Otherwise it
first confirms through `viewerCanMinimize` that it can collapse every earlier
report, creates the new comment, and then minimizes the earlier reports with the
`OUTDATED` classifier. The highest generation stays current, so a late older
run becomes collapsed history at once, and duplicate markers resolve to one
current comment by node ID. Comment bodies are never edited or deleted.

Retries repeat the same procedure, which makes each crash point recoverable:

1. Before posting, the row is still pending with its frozen report.
2. After posting but before recording the ID, the retry finds the marker and
   reuses the comment.
3. During minimization, the retry skips comments already minimized.
4. After remote success but before the database commit, the retry reconciles
   again and records the same result.

GitHub offers no transaction shared with PostgreSQL and no idempotency key for
comment creation, so a lost response can briefly leave two visible reports until
GitHub's reads reflect the earlier write. Deleting a marker comment by hand
while its delivery is still pending can cause it to be recreated; a comment a
human minimized stays minimized. A permanent permission failure or an exhausted
budget can leave minimization incomplete and needs operator action.

## Report content and secrets

`RunChangeDeriver` supplies the same resource changes as the console
([Console](console-ux.md#change-interpretation)). `ChangeReportDeriver` adds
summaries, diagnostics, sanitization, and content limits, and
`MarkdownReportRenderer` formats the result and states what it omitted.

Secrets are redacted by `SecretSentinel` before rendering. Every state-derived
string is untrusted, since URNs and property paths carry names chosen by program
authors and providers. `ReportText` makes each string safe to carry by removing
invisible and direction-changing characters, collapsing it to one line, and
bounding its length. Escaping and mention suppression are format-specific and
belong to `Markdown`, so the JSON form of the report keeps the real values.

`GET /api/updates/{updateId}/report` serves the same report to a reader of the
stack, as `format=json` (the sanitized model) or `format=markdown`.

## Operations

The worker polls every five seconds and processes at most 50 due rows per tick.
Settled rows expire after 30 days, at most 50 per tick. Retention keeps running
while reports are disabled; pending rows pause and no new rows are enqueued.
GitHub keeps the comments after local rows expire.

Structured logs carry `delivery_id`, `update_id`, `stack_id`, and `pr_number`.
Metrics under `statedb.reports` cover queue depth, attempts, deliveries,
failures, and delivery latency. Queue depth is capped at 10,000, so that value
means at least that many pending reports.

Inspect a report by update ID:

```sql
SELECT id, state, attempts, next_attempt_at, target_pr_number, external_id, last_error
FROM report_deliveries WHERE update_id = :update_id;
```

After fixing the cause of a terminal failure, retry the retained row. The frozen
content makes this safe; match the interval to `statedb.reports.max-age`:

```sql
UPDATE report_deliveries
SET state = 'pending', attempts = 0, settled_at = NULL, last_error = NULL,
    next_attempt_at = now()
WHERE id = :delivery_id AND state = 'failed' AND created_at > now() - interval '7 days';
```

The implementation lives in [persistence/vcs](../modules/persistence/src/main/java/app/statedb/persistence/vcs/)
and [vcs](../modules/vcs/src/main/java/app/statedb/vcs/). Tests cover frozen
content, lost responses, duplicate markers, callback replay, installation
spoofing, disconnection, permissions, and secret redaction.
