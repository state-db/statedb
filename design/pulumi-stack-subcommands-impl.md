# `pulumi stack` subcommand support — implementation plan

> Working doc (uncommitted). Captures the gap analysis from a recorder discovery
> pass and the remaining implementation tasks. **Tags, the validation-exception
> consolidation, rename, and history have landed**; schedule/webhook 501s and smoke
> coverage are still pending.

## How we got here

Drove the recorder (`tools/pulumi/recorder`) through every `pulumi stack`
subcommand and regenerated `api-snapshots/pulumi/catalog.md` (commit
`ff2163c`). Decisions from the user:

- **Scope:** core commands (rename, history, tag set/rm) + 501 errors for
  schedule/webhook + smoke-test the already-working commands. Defer
  change-secrets-provider's import-poll and the `decrypt/log-batch-decryption`
  telemetry endpoint.
- **Unsupported error style:** HTTP **501** with Pulumi-style JSON
  `{"code":501,"message":"…"}` (mirrors `UpdateResource#notImplementedJournaling`).

## Subcommand → backend → status

| Subcommand | Backend endpoint(s) | App status |
|---|---|---|
| `init`/`new`, `ls`, `rm`, `export`, `import`, `output` | existing | ✅ works + smoke-tested |
| `select`, `unselect`, `get` (exp), `graph`, bare `stack`, `tag ls`/`get` | `GET stack` / `GET export` | ✅ works, **no smoke test** |
| `tag set` / `tag rm` | `PATCH .../{stack}/tags` | ✅ **DONE** (commit `4f42575`) |
| `history` | `GET .../{stack}/updates` | ✅ **DONE** (legacy paginated response) |
| `rename` | `POST .../{stack}/rename` | ✅ **DONE** (metadata move + checkpoint rewrite) |
| `change-secrets-provider` | `GET export` → `POST import` → `GET .../{stack}/update` poll | ⚠️ deferred (poll endpoint missing) |
| `drift detect` | *(none — CLI errors client-side, never reaches us)* | n/a, nothing to do |
| `schedule list` | `GET .../{stack}/deployments/schedules` | ❌ return 501 |
| `webhook list` | `GET .../{stack}/hooks` | ❌ return 501 |

`tag ls`/`get` already work because `GET stack` returns the `tags` JSONB column.
`output` works today (proven by the stackref smoke test); its extra
`POST .../decrypt/log-batch-decryption` telemetry call 404s harmlessly.

## Already landed (reference for the rest)

- **Tags** (`4f42575`): `PATCH .../tags` on `StackResource` →
  `StackTagWriter.replaceTags` (persistence). Replaces the tag map wholesale
  (CLI sends the full set), serialized with a `PESSIMISTIC_WRITE` row lock so a
  tag edit can't clobber a concurrent update's `active_update_id` — the same
  lease-less guard `StackImporter` uses. Limits: ≤50 tags, key ≤50, value ≤100
  chars → 400. Cedar `stack:SetTags` in the `stack:WriteAccess` bundle.
- **Validation consolidation** (`9c222dc`): one shared
  `io.statedb.persistence.RequestValidationException` + one mapper in
  `io.statedb.pulumiapi.error`. **New input validation throws
  `RequestValidationException` — do not add per-endpoint validation
  exceptions/mappers.** Distinct-status exceptions (`StackLockedException` 409,
  `StackNotFoundException` 404, `StackAlreadyExistsException` 409,
  `AuthorizationDeniedException` 403) keep their own mappers.

## Shared groundwork (landed with rename)

- **Cedar:** added `action "stack:Rename" in ["stack:WriteAccess"]` (resource
  `[Stack]`) to `schema.cedar`. Bundle membership means member/owner inherit it —
  no policy file edits. (`stack:Read` already covers history.)
- **`ProjectUpserter`** (persistence/stack): extracted the `ON CONFLICT DO NOTHING`
  + `SELECT` project upsert out of `StackCreator#upsertProject` into a shared
  `@ApplicationScoped` bean; both `StackCreator` and `StackRenamer` use it.

## Task: rename — `POST /pulumi/api/stacks/{org}/{project}/{stack}/rename` ✅ DONE

- Request `{"newName": "...", "newProject": "..."}` (`AppStackRenameRequest`).
  Empty string = preserve current. CLI sends both (newProject = current project
  for a same-project rename, or a different project for a cross-project move via
  a fully-qualified `org/project/stack`). The CLI discards the response body, but
  the recorded OpenAPI contract types the `200` as `AppImportStackResponse`
  (`{updateId}`), so we return that shape carrying the recorded rename update's id
  (empty only for a no-op rename) rather than a bare `{}` — keeps other protocol
  clients able to decode it. (The contract is *not* a stack summary;
  `AppStackSummary` is unused here.)
- **The rename is NOT a metadata-only move.** Verified against the Pulumi source
  (`v3.242.0`, checked out locally): `cloudBackend.RenameStack` makes a single
  rename API call and does **no** client-side state rewrite — unlike the diy
  backend, which rewrites the checkpoint locally via `edit.RenameStack`. So for a
  cloud-compatible backend the **server** owns that rewrite. A workbench spin
  proved a metadata-only rename breaks real stacks: the `service` secrets provider
  rebuilds its decrypt URL from `secrets_providers.state.{owner,project,stack}`
  (Pulumi's `NewServiceSecretsManagerFromState`), so `pulumi stack output` POSTs to
  `.../<oldname>/batch-decrypt` → 404; and every resource URN still names the old
  stack, so the next `up` plans to replace the whole stack. Internal refs are fine
  (we key on the stack UUID — `export` returns 200) — the breakage is purely the
  name strings Pulumi bakes into the checkpoint.
- **`StackRenamer`** (persistence/stack, `@Transactional`):
  - target project = `newProject` or current; target name = `newName` or current;
    no-op if both unchanged.
  - takes a `PESSIMISTIC_WRITE` row lock (lease-less write, like `StackImporter`);
    409 (`StackLockedException`) if `activeUpdateId != null` or an `IN_PROGRESS`
    update exists.
  - `projectUpserter.upsert(orgId, targetProject)` (shared with `StackCreator`),
    set `projectId`/`name`, `persistAndFlush`; unique violation →
    `StackAlreadyExistsException(org, targetProject, targetName)` (409). Owner
    cannot change — the org is fixed by the request path (Pulumi rejects owner
    changes client-side too).
  - **checkpoint rewrite:** if the stack has state, `assemble` the current
    `DeploymentV3`, rewrite it via **`CheckpointRenamer`**, and reinstall as a
    fresh compacted base snapshot (same mechanism as `StackImporter`; replay
    starts after the new compaction boundary, so stale-URN journal rows are
    superseded — simpler than rewriting N journal deltas in place).
  - **reserved tag:** on a cross-project move, rewrite the `pulumi:project` tag (if
    present) to the new project so `GET`/list metadata doesn't contradict the
    stack's actual project.
- **`CheckpointRenamer`** (persistence/stack): pure JSON rewrite mirroring
  `edit.RenameStack` plus the cloud-only secrets bit. For every resource in
  `resources[]` and `pending_operations[]`, rewrite the stack/project component of
  `urn`, `parent`, `dependencies[]`, `propertyDependencies{}`, `deletedWith`,
  `replaceWith[]`, and the `provider` ref; the root `pulumi:pulumi:Stack`
  resource's name is regenerated as `<project>-<stack>`. Also rewrite
  `secrets_providers.state.{project,stack}` when `type == "service"`. Leaves
  user-set property values alone (e.g. a `keepers.project` input is not a URN).
- Resource method on `StackResource` (`@Path("/rename")`,
  `@Authorize(action="stack:Rename", loader=StackEntityLoader.class)`). If both
  newName and newProject are blank → `RequestValidationException("no rename to apply")` (400).
- The rename is recorded as a terminal `stack_updates` row (kind `rename`, status
  `succeeded`, version `MAX+1` under the stacks row lock), inserted inline like
  `StackImporter` — no lease/poll. `stack.version` is bumped. The returned
  `updateId` is this row's id. We record it for audit/console regardless of the
  CLI ignoring the body — but it is **filtered out of the CLI-facing `GET
  .../updates`** (see history task) so `pulumi stack history` stays byte-compatible
  with stock Pulumi, which never surfaces renames in history.
- Tests: `StackRenameContractTest` — happy path (dev→staging, GET new name 200,
  old name 404), checkpoint rewrite same-project + cross-project (export asserts
  URNs + secrets state rewritten), 401/404/409 (duplicate target)/400. Validated
  live on the workbench: rename → `output --show-secrets` decrypts, `preview`
  shows "6 unchanged" (no replacement churn), cross-project move rewrites the
  project component.

## Task: history — `GET /pulumi/api/stacks/{org}/{project}/{stack}/updates` ✅ DONE

- Query params `page`, `pageSize` (legacy format; CLI sends `pageSize=10&page=1`).
  `output-type` unset ⇒ legacy response `{"updates":[...], "itemsPerPage": N}`.
  `page<=0` ⇒ all rows; else offset `(page-1)*pageSize`, limit `pageSize`.
- New `StackHistoryResource` (own class, like `StackExportResource`),
  `@Authorize(action="stack:Read", loader=StackEntityLoader.class)` (already in
  ReadOnlyAccess — no Cedar change).
- **`StackHistoryFinder`** (persistence/update): Panache
  `StackUpdate.list("stackId = ?1 and kind not in ?2 order by version desc", stackId, List.of(Kind.PREVIEW, Kind.RENAME))`,
  paginate with `.page(Page.of(page-1, pageSize))`. Exclude **previews** (not real
  deployments) and **renames**. Renames are recorded as `stack_updates` rows for
  audit/console, but stock Pulumi never shows a rename in `pulumi stack history`,
  so dropping them here keeps CLI output byte-compatible. (Grounded in a file/diy
  backend spin: `diyBackend.renameStack` relocates history files and never calls
  `addToHistory`; the service backend handles rename as a separate API op too. The
  console surfaces rename events instead.)
- DTO per row (`apitype.UpdateInfo`, `@JsonInclude(NON_NULL)`):
  `kind` (dbValue), `message`, `environment`, `config`,
  `result` (IN_PROGRESS→`in-progress`, SUCCEEDED→`succeeded`, FAILED/CANCELLED→`failed`),
  `startTime`/`endTime` (epoch **seconds** — confirmed against the diy backend's
  on-disk `.history.json`; endTime 0 if null), `version`, `resourceChanges` (omit
  for now). `config` carries through verbatim from `stack_updates.config` (stored
  from `UpdateProgramRequest.config`, which *is* Pulumi's `map[string]ConfigValue`
  — same wire shape as `UpdateInfo.config`, so it round-trips; secret values stay
  ciphertext); `environment` is lifted from `metadata.environment`. An absent map
  serializes as `{}`. **Emit our real per-stack `version`** — the diy backend always
  writes `version: 0`, but the service backend uses monotonic versions and so do
  we; do not mirror diy's `0`.
- **`output-type` (paginated mode) is a deliberate non-goal.** The OpenAPI subset
  documents an `output-type` query param ("unset ⇒ legacy, set ⇒ paginated") but types
  the paginated `200` as a bare `type: object` — the shape's fields are *not* captured.
  The Pulumi CLI never sends it (recorder + live workbench confirm `stack history`
  omits it), and no other targeted client uses it. Implementing it now would mean
  reverse-engineering an unspecified shape for a caller that doesn't exist, so we serve
  only the legacy format. Revisit if a real client needs pagination.
- `pageSize` validation matches the contract ("must be >= 1 when page > 0", 400 on
  invalid): `pageSize < 1` → 400, oversized `pageSize` clamped to a 100-row ceiling,
  `page <= 0` returns all rows.
- Tests: `StackHistoryContractTest` — empty stack returns `{"updates":[],"itemsPerPage":...}`;
  after a completed update, one row with correct kind/result/version; previews **and
  renames** excluded (rename a stack with history, assert the `rename` row does not
  appear); 401/404.

## Task: 501 for schedule + webhook

- New `UnsupportedFeatureResource` under the stack base path with:
  - `GET /deployments/schedules`
  - `GET /hooks`
- Each returns 501 with
  `{"code":501,"message":"stack <schedules|webhooks> are not supported by this backend"}`.
  No `@Authorize` (pure stub); `TokenAuthFilter` still enforces 401 globally.
- Tests: assert 501 + message; assert 401 without token.

## Task: smoke tests — 100% supported-command coverage

Extend `modules/pulumi-cli-smoke/.../PulumiCliSmokeIT.java` (real Pulumi CLI in
Docker). Add coverage for the supported-but-untested commands and the new ones:

- `select` / `unselect`, bare `stack`, `stack get`, `stack graph <file>`.
- `stack tag set/ls/get/rm` (round-trip a tag, assert it appears then disappears).
- `stack rename` (rename, assert `stack ls` shows new name).
- `stack history --json` (assert at least one update after an `up`).
- Assert `stack schedule list` / `stack webhook list` fail cleanly (non-zero exit,
  not a hang) — the 501 surfaces as a CLI error.

Group related assertions into a couple of new `@Test` methods to keep container
startups down (each test spins a Pulumi container).

## Recorder note (done)

`entrypoint.sh` appends the new subcommands as commands 11–30 and leaves 01–10
byte-stable (no fixture churn). The `stubs.go` `renameStack` re-keys the stack
record so post-rename `ls`/`rm` resolve. Committed in `ff2163c`.
