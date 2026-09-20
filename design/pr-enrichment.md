# Pull-request enrichment — design proposal

statedb posts a focused summary of a `pulumi preview` (and optionally `up`) onto
the originating pull request, plus a commit-level pass/fail signal. Think
Atlantis or Terraform Cloud's plan comment, scoped to what statedb already
knows.

Status: **in progress**. Slices 0 and 1 are implemented; later slices remain a
proposal. The seams, invariants and slice order are settled enough to build
against; items under "Open questions" are not. Items marked **[evidence]** are
unverified and must be proven before the code that depends on them is written.
Linkage (what the Pulumi CLI sends, and when) has been verified against Pulumi
source and is stated as fact.

Related docs: `architecture.md` (module layout, URL surfaces),
`pulumi-state-lifecycle.md` (`stack_updates`, `update_events`),
`secrets.md` (why the service keyset is *not* the credential store here),
`db-schema.md`.

## Vocabulary

One word per concept, chosen to match what already exists rather than to invent
a category:

- **`vcs`** — the external system that hosts the repository and the pull
  request (GitHub, GitLab, ...). This is the Pulumi CLI's own term in the
  metadata we ingest (`vcs.kind`, `vcs.owner`, `vcs.repo`) and is already
  surfaced by the console in `modules/web/src/routes/run-detail.tsx`. Note that
  `vcs.kind` is a **hostname**, not a vendor enum — see "Host identity".
- **`PullRequest`** — the thing we write on. GitLab's merge request and
  Gerrit's change normalize to this at the adapter boundary.
- **`Report`** — what we publish. `ReportPublisher` is named for its payload,
  not its destination, which is why it needs no vendor qualifier.

Avoid "provider": `OidcProvider` is an entity in this repo and "provider" means
something else again in Pulumi. Avoid "forge": accurate in FOSS circles, but not
vocabulary a self-hosting operator reaches for.

---

## Goals

- A preview run in CI leaves one **sticky, focused comment** on the PR: change
  counts, the resources that change, the properties that change, a link to the
  console. Deterministic, diffable, no prose.
- A **commit-level signal** (check run or commit status) that is safe to use as
  a required check — which means it must go `pending` on start, not only
  report on completion.
- **Host-agnostic.** GitHub first; GitLab/Bitbucket/Gitea are adapters, not
  rewrites.
- **No AI in the application.** The rendered report is also available as JSON so
  anything that wants to summarise it can, outside our process.
- **Off by default, zero egress** unless an operator configures a connection.
  Air-gapped installs must be unaffected.

## Non-goals

- Running Pulumi for the user. We enrich a run that happened elsewhere; we are
  not a deployment runner. (This is why a check-run "re-run" button is not a
  motivating feature: honouring it needs `requested_action` webhooks and
  something to re-run, and we have neither.)
- Inbound webhooks in the first waves.
- Policy evaluation, drift detection, or cost estimation in the comment.

---

## The four invariants

Everything below exists to hold these. They are the reason the delivery module
is deep rather than a thin wrapper over a REST call.

1. **Verified target.** No external write is ever addressed by CLI-supplied
   metadata. The target is resolved and confirmed against the VCS host, then
   frozen.
2. **Sanitized report.** No raw engine-event value reaches a renderer or a
   publisher. Sanitization happens at `ChangeReport` construction.
3. **Complete lifecycle.** Every status transition an update can make produces
   the right signal, including the ones that do not pass through
   `StackUpdateCompleter`.
4. **Latest run wins.** A slow or retried delivery can never overwrite the
   result of a newer run for the same target.

---

## Architecture: three seams, one of which does IO

```
update starts ────► Deliver(pending)
update terminal ──► Derive (pure) ──► Render (pure) ──► Deliver(final)
                    ChangeReport       RenderedReport    ReportPublisher
```

**1. Derive — pure, no IO.**
`(StackUpdate, List<StoredEvent>) -> ChangeReport`. Counts from
`summaryEvent.resourceChanges`; per-resource rows from `metadata.op` and
`detailedDiff`. Pure: events are *passed in*, not queried (see "Derivation
input"). Sanitization is part of construction, not a later pass.

**2. Render — pure.**
`ChangeReport -> RenderedReport { title, body, summaryLine, truncated }`.
Markdown first. The report model is format-agnostic, so a GitLab-flavoured or
plain-text renderer is an alternate implementation, not a fork.

**3. Deliver — the only IO.**
`ReportPublisher` port. Implementations: `InMemoryVcs` (tests),
`GitHubPublisher`, later `GitLabPublisher`. Credentials come from a separate
`VcsTokenSource` seam (`StaticTokenSource` | `InstallationTokenSource`); that
seam is mandatory regardless of the GitHub decision, because GitLab and
Bitbucket have no App-with-JIT-token equivalent.

---

## Linkage: what the CLI actually sends

Verified against Pulumi source (`pkg/backend/updates.go`,
`pkg/cmd/pulumi/metadata/metadata.go`, `sdk/go/common/util/ciutil/`), not
inferred. This replaces the earlier assumption that a PR number generally
arrives with the run.

`GetUpdateMetadata` composes `metadata.environment` from four independent
sources. Only the first is reliably present:

| Source | Keys | When populated |
|---|---|---|
| Local git repo | `git.head`, `git.headName`, `git.dirty`, `git.committer`, `git.author` (+`.email`) | whenever a git repo is found at the project root |
| `origin` remote URL, parsed | `vcs.owner`, `vcs.repo`, `vcs.kind`, `vcs.root` | whenever the repo has an `origin` remote on a recognised host |
| CI detection | `ci.system`, `ci.build.id`, `ci.build.number`, `ci.build.type`, `ci.build.url`, `ci.pr.headSHA`, `ci.pr.number` | **only when a CI system is detected** |
| CLI/exec | `exec.kind`, `exec.agent`, `updatePlan`, `stack.environments`, `pulumi.*` | always |

Three consequences that shape the design:

**1. `ci.*` is absent far more often than present.**
`addCIMetadataToEnvironment` returns immediately when `ciutil.DetectVars()`
finds no CI system, and detection is pure env-var presence (`GITHUB_ACTIONS`,
`GITLAB_CI`, `CIRCLECI`, `TF_BUILD`, ...). A local `pulumi preview` therefore
carries **no** `ci.*` keys at all — no PR number, no build number.

**2. Most CI systems are detected but not read.**
`ciutil` has two tiers. A handful of systems have a custom `DetectVars`
(GitHub Actions, GitLab, CircleCI, Buildkite, Azure Pipelines, Bitbucket
Pipelines, Codefresh, Travis, Jenkins). Everything else is registered as a bare
`baseCI`, whose entire implementation is:

```go
func (d baseCI) DetectVars() Vars { return Vars{Name: d.Name} }
```

`ci.system` is then set unconditionally, while every other `ci.*` key goes
through `addIfSet` with an empty value and is dropped. **Drone is one of these**
— `baseCI{Name: Drone, EnvVarsToDetect: []string{"DRONE"}}` — so a Drone build
can never carry a PR number, a build id, or a head SHA, no matter how the
pipeline is configured.

This is confirmed by an observed Drone run, whose entire metadata bag was:

```text
vcs.kind, vcs.owner, vcs.repo, vcs.root
git.head, git.headName, git.committer, git.committer.email, git.dirty
ci.system
```

Note what is present and what is not: `ci.system` alone from the CI tier, and
no `ci.build.*` or `ci.pr.*` at all.

**2b. Even on the rich tier, `ci.pr.number` is conditional.**
`githubActionsCI.DetectVars` sets the PR number only when
`GITHUB_EVENT_NAME == "pull_request"`, and only if it can read and parse the
event payload at `GITHUB_EVENT_PATH` (it returns nil on any error). So
`pull_request_target`, `merge_group`, `push`, `workflow_dispatch` and
`issue_comment` runs all produce no PR number.

**3. `git.head` is the wrong SHA on GitHub Actions PR builds.**
Actions checks out a synthetic merge commit, so the repo HEAD that becomes
`git.head` is *not* the PR head. Pulumi handles this explicitly: on a
`pull_request` event, ciutil overrides its SHA with
`event.pull_request.head.sha` and publishes it as `ci.pr.headSHA`. Pulumi's own
constant says so — "This is needed since the CI server will run at a different,
merge commit."

So **`ci.pr.headSHA` is the authoritative commit when present, and `git.head`
is a fallback that may be a merge commit**. A commit status posted to a merge
SHA is invisible on the PR, and that SHA may not resolve to a PR at all.

### Why Pulumi Cloud still manages to link PRs

Given the above, Pulumi Cloud cannot be relying on the CLI for PR association
in the general case — a Drone build sends it no PR number either. The likely
mechanism is server-side: the GitHub App installation lets the service resolve
`git.head` within `vcs.owner`/`vcs.repo` back to a pull request itself.

That is the same thing our design does, and it is the strongest argument for
making resolve-by-commit the primary path. It also means the App is doing real
work here beyond credentials: without host API access there is no reliable way
to learn the PR at all for most CI systems.

### Host identity: `vcs.kind` is a hostname

`gitutil.TryGetVCSInfo` parses the `origin` remote and assigns
`vcsKind = groups["host_name"]` — the host taken straight out of the URL. The
observed value is `github.com`, not `github`. A self-hosted GitHub Enterprise
or GitLab instance yields its own hostname.

So `VcsKind` cannot be an enum keyed directly off `vcs.kind`. The mapping from
hostname to adapter is **operator configuration**, carried on the
`vcs_connections` row: a connection declares its host(s), and that is what makes
GitHub Enterprise Server and self-hosted GitLab supportable at all. Treat
`vcs.kind` as a hint that must match the bound connection's host, never as the
selector for which adapter to run.

### The supported escape hatch

`ciutil` has a generic detector, deliberately **first** in the detector list so
it takes precedence over real detection:

| Env var | Becomes |
|---|---|
| `PULUMI_CI_SYSTEM` | `ci.system` — **required**, or none of the others apply |
| `PULUMI_PR_NUMBER` | `ci.pr.number` |
| `PULUMI_CI_PULL_REQUEST_SHA` | `ci.pr.headSHA` |
| `PULUMI_CI_BUILD_ID` / `_NUMBER` / `_TYPE` / `_URL` | the `ci.build.*` keys |

This removes the need for a statedb-side endpoint for explicit coordinates:
**any pipeline on any host can supply the PR number and head SHA to an
unmodified CLI through environment variables**, which preserves the repo's
"users point an unmodified Pulumi CLI at state-db" contract. It is the answer
for GitLab, Jenkins, and for GitHub Actions events other than `pull_request`.

**`PULUMI_CI_SYSTEM` is load-bearing and is the most likely thing to get
wrong.** The generic detector's `EnvVarsToDetect` is `["PULUMI_CI_SYSTEM"]`, so
it only matches when that variable is set. Exporting `PULUMI_PR_NUMBER` and
`PULUMI_CI_PULL_REQUEST_SHA` *without* it changes nothing at all: the generic
detector does not match, the real CI detector matches instead, and on the
`baseCI` tier it discards everything. This has already been observed in
practice on Drone — the variables were exported and had no effect.

Worked example for Drone, where nothing else can supply a PR number
[evidence: confirm the Drone variable names against the pipeline's own Drone
version before documenting them]:

```text
PULUMI_CI_SYSTEM=drone
PULUMI_PR_NUMBER=${DRONE_PULL_REQUEST}
PULUMI_CI_PULL_REQUEST_SHA=${DRONE_COMMIT_SHA}
PULUMI_CI_BUILD_ID=${DRONE_BUILD_NUMBER}
PULUMI_CI_BUILD_URL=${DRONE_BUILD_LINK}
PULUMI_CI_BUILD_TYPE=${DRONE_BUILD_EVENT}
```

Other caveats:

- Because the generic detector wins, setting it *inside* GitHub Actions
  disables the automatic event-payload PR detection — so a pipeline that sets
  it must supply `PULUMI_PR_NUMBER` and `PULUMI_CI_PULL_REQUEST_SHA` itself.
- On Drone, `git.head` is likely already the real PR head rather than a merge
  commit, since Drone clones `refs/pull/N/head`. The Actions merge-commit
  hazard is Actions-specific. [evidence: confirm against the pipeline's clone
  step.]

There is a second set of env vars (`PULUMI_VCS_REPO_OWNER`, `PULUMI_GIT_HEAD`,
...) but they are read **only when no git or Mercurial repository is found**.
With a repo present they are silently ignored, so they are not a general
override and should not be documented as one.

---

## Invariant 1: target resolution

A repository binding says *which repo* a stack may post to. It says nothing
about *which PR* or *which commit* — and both of those arrive as
`metadata.environment`, supplied by whoever holds a statedb CLI token. Binding
alone therefore still permits a stack writer to comment on an unrelated PR in
the bound repo, or publish a passing status for a commit they do not own.

Metadata is a **hint**, never an address. Resolution:

1. `vcs_repo_bindings` maps project/stack to a repository on the VCS host, by
   the host's **canonical repository id** (GitHub's numeric/node id), not `owner/name` —
   names are mutable and reusable.
2. Choose the candidate commit: `ci.pr.headSHA` when present, else
   `git.head`. Never prefer `git.head` when both exist — see "Linkage".
3. Resolve to a PR. When `ci.pr.number` is present, confirm it with
   `GET /repos/{o}/{r}/pulls/{n}`; otherwise resolve by commit with
   `GET /repos/{o}/{r}/commits/{sha}/pulls`. Resolution by commit is the
   **primary** path, not the fallback, because the PR number is absent in most
   runs.
4. Confirm the resolved PR's base repository id equals the bound repository id,
   and that its **current** head SHA matches the candidate.
5. Persist the confirmed `{repo_id, pr_number, head_sha}` as the delivery's
   frozen `PullRequestTarget`. Later writes use the frozen target, never the
   metadata.

If resolution fails or disagrees, the delivery goes terminal as `unverified`
and nothing is posted. Silence is the correct failure mode.

Consequence for the slice plan: **there is no safe intermediate state where we
post using metadata alone.** Target resolution requires host access, so the
`InMemoryVcs` in Slice 2 must model PR lookup as well as comment upsert.

### Rules that still need deciding

- **Fork PRs.** The PR lives on the base repo; the head commit lives in the
  fork. A comment on the base PR is fine. A *status on the head SHA* targets a
  repository the installation may not cover. Decide: skip the status for fork
  PRs, or post it to the base repo if the host allows.
- **Runs with no usable SHA.** A run with neither `ci.pr.headSHA` nor a
  `git.head` that resolves to an open PR is not an error — it is the common
  case for local previews. It must settle as `unverified` quietly and must not
  retry, or every developer's local preview becomes queue churn.
- **Dirty trees.** `git.dirty` reports uncommitted changes, meaning the preview
  does not correspond to the commit we would post against. Decide whether to
  suppress delivery or label the report.
- Whether a stale head SHA (force-push between run and delivery) is
  `unverified` or a soft skip.

---

## Invariant 2: sanitization is a security interface

Derivation consumes `metadata.old`, `metadata.new` and `detailedDiff`, which are
resource state. The output is posted to a surface with a different and usually
wider audience than the console. Sanitization is therefore part of the report
contract, defined **before** Slice 1 — not renderer polish.

**Default: emit property *paths* and change kinds, never property *values*.**
This is the safest default, and it happens to solve most of the truncation
problem too. Value rendering, if ever added, is opt-in per binding and subject
to everything below.

### Two layers, not one

Implementing this showed the contract has to split in two, and the earlier
single-layer phrasing — "the `ChangeReport` constructor must enforce" all of it
— was wrong. Mention suppression and markdown escaping **cannot** happen at
construction: they would corrupt the JSON representation. A resource name may
legitimately contain `@`, `#`, `|` or a backtick, and a consumer of
`GET /api/updates/{id}/report?format=json` must receive the real URN, not one
with its characters rewritten. Those transforms are also format-specific —
`#123` auto-links in GitHub-flavoured markdown and means nothing in plain text.

So:

**Layer 1 — neutralization, enforced at `ChangeReport` construction**
(`ReportText`). The deriver performs the lossy transform because it owns the
report's `truncated` flag; every report component constructor rejects a string
that bypassed it. This makes a string safe to *carry* and safe to *read*.
Identical for every format, and therefore correct to bake into the value object.

- **Structural secret redaction.** Pulumi marks secret values in state with a
  signature-keyed wrapper object; redaction keys off that structure, not off
  property-name heuristics. See "The secret sentinel" below for the pinned
  constants. Anything unrecognised is treated as secret, not as plaintext.
- **Control characters, bidi, and invisibles.** Keyed on Unicode general
  category rather than a hand-listed set: Cc drops (its whitespace members
  collapse to a space), Cf drops entirely — which covers the bidi overrides and
  isolates and the zero-width characters together — unpaired surrogates become
  `U+FFFD`, and the separator categories collapse to a space. The result is one
  line.
- **Closed enums for `op` and `diffKind`.** An unrecognised wire value becomes
  `UNKNOWN` rather than being carried through, so neither can ever ferry
  injected text into a renderer.
- **`hideDiffs` is honoured.** It sits on `StepEventStateMetadata` — on
  `metadata.old`/`metadata.new`, not on the step — and is Pulumi's own "do not
  display a diff here". The console honours it; a report with a wider audience
  has strictly more reason to. A hidden path hides its descendants with it.
- **Length limits** at three levels: per field, per row (properties per
  resource), and per body, with a deterministic truncation marker and the
  `truncated` flag set. GitHub's comment limit is ~65 KB; the body budget sits
  well under it so a truncated report never becomes a failed POST. Field
  budgets are in codepoints, so truncation lands on a character boundary by
  construction and still bounds bytes.

**Layer 2 — escaping, at render** (`Markdown`, `MarkdownReportRenderer`).

- **Every state-derived fragment is emitted as an inline code span**, rather
  than escaped character by character. Inside a code span GFM interprets
  nothing: `@everyone` does not notify, `#123` does not link, a bare URL does
  not autolink, `<img onerror=…>` is not HTML. That neutralizes the whole class
  at once and stays correct as GFM grows, where an escape list would have to be
  maintained against it. Two things the span does not cover and the renderer
  does: a fence longer than any backtick run in the content, and `\|` for a
  pipe, which GFM splits table rows on *before* it parses cells.
- **URL policy.** The only link emitted is the console link the caller
  constructed; it must be an absolute `http(s)` URL with a host. Nothing from
  state becomes a link target, and a hostile URL in a URN is still *shown* —
  a reader should see the URN as it is — but only as text.
- **Diagnostic messages are not rendered**, only counts. Provider free text is
  the one place a value read out of state can reappear without passing through
  anything that knows it is a value.

The test for mention suppression asserts the real invariant: no `@` or `#`
appears *outside* a code span in a rendered body.

**Default: emit property paths and change kinds, never property values.**
`ChangeReport` is a sanitized value object and raw `JsonNode` payloads do not
appear on its surface. A path may name a secret property; paths are emitted,
only values are withheld. `PropertyChange.secret` records *that* a path held a
secret — metadata the Pulumi CLI itself shows — without the value.

### The secret sentinel

Verified against Pulumi SDK v3.259.0 (`sdk/go/common/resource/sig/sig.go`,
re-exported from `sdk/go/common/resource/properties.go`) and against the
recorded fixtures under `tools/pulumi/recorder/fixtures/by-command/`.

A special value is an **object** carrying a reserved key whose value is a
signature string. The key is the same for every special type:

```text
SigKey = "4dabf18193072939515e22adb298388d"
```

`resource.HasSig` requires the signature to be a **string** equal to the match,
so a non-string value at `SigKey` is not a valid marker. The signatures:

| Signature constant | Value | Meaning |
|---|---|---|
| `SecretSig` | `1b47061264138c4ac30d75fd1eb44270` | secret |
| `OutputValueSig` | `d0e6a833031e9bbcd3f4e8bde6ca49a4` | output value; **secret when it carries `"secret": true`** |
| `ResourceReferenceSig` | `5cf8f73096256a8f31e491e813e4eb8e` | resource reference |
| `AssetSig` | `c44067f5952c0a294b673a41bacd8c17` | asset |
| `ArchiveSig` | `0def7320c3a5731c473e5ecbe6d01bc7` | archive |
| `ByteStringSig` | `803fd3297a5875dc03ca845dda5d2a98` | non-UTF-8 string, base64-encoded |

**There are two secret-bearing shapes, not one.** A redactor that only knows
`SecretSig` leaks the second:

1. `{SigKey: SecretSig, "value": ...}` — `MarshalPropertyValue`'s `IsSecret`
   branch. On the service wire the payload is the encrypted form instead:
   `apitype.SecretV1` declares `ciphertext` and `plaintext` as alternatives
   under the same `SigKey` field, and `pkg/resource/stack/deployment.go`
   rejects a secret carrying neither or both.
2. `{SigKey: OutputValueSig, "secret": true, "value": ...}` — the same
   function's `KeepOutputValues && UpgradeToOutputValues` path, and its
   `IsOutput` branch, which attaches `"secret"` whenever the output is secret.
   The `value` here is **plaintext**.

An output whose own `secret` flag is false can still contain a secret wrapper
inside its `value`. Pulumi's `PropertyValue.ContainsSecrets` checks both the
flag and the output element recursively; the sentinel must do the same.

Observed in the recorded preview fixture
(`04-preview/0012_POST_..._events_batch.json`), at
`resourcePreEvent.metadata.old.outputs.password` among others:

```json
{"4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
 "ciphertext": "[secret]"}
```

(`[secret]` is the recorder's normalization of a volatile value, not a Pulumi
literal.) Secrets also occur nested — the same fixture carries them under
`outputs.__pulumi_raw_state_delta.obj.ps.*` — so the walk must be recursive and
must not assume a secret sits at a top-level property.

Consequences for the sanitizer:

- Recognition is `SigKey` present **and** its value is a string. Treat
  `SecretSig`, and `OutputValueSig` with `"secret": true`, as secret.
- **Any object carrying `SigKey` with a signature we do not recognise is
  treated as secret**, not as a plain object to walk into. Pulumi's own
  unmarshaller refuses an unknown signature outright
  (`"unrecognized signature '%v' in property map"`), so a future signature is a
  shape we do not understand, and the safe reading of a value we do not
  understand is that it may be sensitive.
- A property *path* may legitimately name a secret property. Paths are emitted;
  only values are withheld. Under the values-never-emitted default this bullet
  is belt-and-braces for the opt-in value rendering that may come later, and
  for anything a renderer might otherwise inline.

---

## Invariant 3: lifecycle covers every transition

A required check is only meaningful if it goes `pending` when the run starts.
Otherwise an earlier success for the same SHA and context stays green while a
rerun is in flight, and a merge can land on a stale pass. So there are **two**
dispatch points, start and terminal.

The terminal one cannot hang off `StackUpdateCompleter`. Verified call sites
that move an update to a terminal status:

| Path | Entry point | Status |
|---|---|---|
| CLI `/complete` | `LeaseResource` -> `StackUpdateCompleter` | `succeeded` / `failed` |
| CLI graceful cancel | `LeaseResource` -> `StackLockCanceller.gracefulCancel` | `cancelled` |
| Forced cancel by update | `UpdateResource` -> `forceCancelUpdate` | `cancelled` |
| Forced cancel by lock | `AdminOperationsResource` -> `forceCancelLock` | `cancelled` |

`StackUpdateCompleter` explicitly rejects anything but `SUCCEEDED`/`FAILED`, so
a completer-only hook silently never reports cancellations.

**Use a predicate sweep, not a call-site enqueue.** This is the repo's own
idiom: `EventArchiveFolder` documents why ("Eligibility is a predicate, not a
queue") — a crash mid-work rolls back and leaves the item pending, there is
nothing to reclaim, and no job rows to reap. Same reasoning applies exactly.

- Pending set: updates that are terminal, have a binding, and have no settled
  delivery for the current generation. A settled marker plus a partial index
  keeps the set to outstanding work only, including for stacks that will never
  have a binding.
- Claim with `SKIP LOCKED` on the delivery row; the unique key on
  `(target, surface, generation)` is the backstop.
- Takes none of the locks compaction and updates serialize on — like event
  folding, this is observability-adjacent work with no ordering invariant
  against `stack_state`.

The `pending` signal dispatches from update start, which has a single entry
point today; it is still routed through the same delivery table so
supersession applies to it too.

---

## Invariant 4: latest run wins

A sticky key prevents duplicate comments. It does not prevent **stale writes**:
a retried or slow delivery for update N can patch the comment, or publish a
status, after update N+1 has already reported. Multiple workers make this
reachable even when rows are created in order.

- Every delivery carries a **generation**: the `stack_updates.version` of the
  run it reports (monotonic per stack).
- Uniqueness and reconciliation are on `(binding, pr_number, stack, surface)` —
  `surface` being `comment` / `status`, which have different external identities
  and must not contend.
- On enqueue, any non-settled delivery for the same target+surface with a lower
  generation is marked `superseded` **before** any external write.
- Every worker re-checks generation currency inside the claim transaction,
  immediately before the outbound call, and abandons if it is no longer the
  highest. The window is narrowed, not closed, by the DB; the host write is
  the last step and is idempotent by external id.

Required test: out-of-order completion and retry — worker A stalls on
generation 1, generation 2 publishes, A resumes and must write nothing.

---

## Derivation input: use the event reader, not the table

Terminal runs are folded after a grace period by `EventArchiveFolder`, which
compresses the stream into `update_event_archives` and deletes the non-summary
rows — **leaving the `summary` rows behind**. So a report built from a direct
`update_events` query would not fail loudly; it would silently produce a
summary-only report for every run older than the grace period. That is the
worst possible failure shape for this feature.

`UpdateEventReader` is the authoritative seam: one native statement whose
`UNION ALL` reads rows and archive under a single snapshot, with archive
presence — not row emptiness — deciding the source.

Derivation therefore takes `List<StoredEvent>` as a parameter and stays pure.
`RunChangeDeriver` is the sole owner of Pulumi event-to-change semantics. It
produces one redacted `RunChanges` model for both the console and report code.
The console renders that model; `ChangeReportDeriver` projects it into the
smaller value-free report model. Neither presentation surface independently
interprets `detailedDiff`, `diffs`, `hideDiffs`, replacement substeps, or secret
wrappers. Raw events remain available only for the chronological log, whose
lifecycle and diagnostic formatting is not a second change derivation.

The reader needs a **report-oriented read method** alongside the existing
`page(stackId, updateId, after, limit)`: paging an archived run re-decodes the
whole blob per page, which is wrong for a whole-stream consumer. Either a
streaming method or a decode-once whole-stream method, with an explicit
event-count bound. That bound caps how many payload trees are retained and, on
the archive path, stops inflation after the probe event. It is not a byte-level
heap guarantee: JDBC still materializes the compressed archive, and one event
payload has no separate size limit. A hard byte bound requires chunked archive
storage or a separately bounded streaming representation; do not describe the
current method as providing one.

---

## Persistence sketch

| Table | Purpose |
|---|---|
| `update_vcs_links` | the *hint* from CLI metadata: `{vcs_kind, owner, repo, pr_number, head_sha, source}`. Never an address. |
| `vcs_connections` | org-scoped connection: kind, credential storage mode, capabilities, status, version. |
| `vcs_repo_bindings` | project/stack -> canonical repository id on the host. The authorization boundary. |
| `report_deliveries` | verified frozen `PullRequestTarget`, surface, generation, state, attempts, backoff, external ids, last error. |

DDL in Flyway migrations (`V003__...` onward). Entities follow repo ORM idioms:
`BaseEntity` / `TimestampedEntity`, Panache finders, no generic DAOs. The
predicate sweep and the supersession update are the places where explicit SQL
is likely justified; keep the reason in javadoc.

Delivery states: `pending`, `claimed`, `delivered`, `superseded`, `unverified`,
`abandoned`, `failed`.

---

## Credential storage and lifecycle

The service secrets keyset is **key material for stack secrets**, documented as
single-key with no rotation. It is not a credential store and must not become
one by implication. VCS credentials are a separate concern with a bigger
blast radius (a write-scoped token for a customer's source host).

To specify before Slice 3:

- **Storage modes.** Support both: (a) encrypted DB column, (b)
  external/environment-supplied, referenced by name. Operators who will not put
  a write credential for their source host in Postgres must have the second option.
- **AEAD scheme** for mode (a): a *dedicated* keyset, not the service-secrets
  one, with associated data binding ciphertext to
  `vcs-credential:{org}:{connection}:{version}` so it cannot be replayed
  across connections or orgs.
- **Rotation and revocation.** Connection `version` increments on credential
  replacement; cached installation tokens keyed by version are invalidated.
  Define what happens to queued deliveries when a connection is disabled,
  deleted, or rotated — proposed: disabled/deleted marks queued deliveries
  `abandoned` rather than letting them drain with a credential the operator
  believes is gone.
- **Redaction.** Credential material is never returned by the console API and
  never logged; connections expose a masked fingerprint and validity state only.
- **Health.** A connection carries a last-verified timestamp and an error state,
  so an expired PAT or uninstalled App surfaces in the console instead of as
  silent delivery failures.

---

## Credential decision: GitHub App vs machine-user PAT

Verified (GitHub docs, 2026-09-18):

- `POST /repos/{owner}/{repo}/issues/{issue_number}/comments` is listed under
  **both** "Issues: write" and "Pull requests: write" for fine-grained PATs. For
  PRs, "Pull requests: Read and write" alone suffices.
- A PAT can post commit statuses (`POST /repos/{owner}/{repo}/statuses/{sha}`,
  "Commit statuses: write").
- GitHub has exactly two actor types that can write: a user, or an App
  installation. No service-principal concept. (`GITHUB_TOKEN` in Actions is
  itself an installation token for the GitHub Actions App.) Deploy keys are
  git-only.

**[evidence] Check runs and PATs.** GitHub's check-run documentation both states
that creating a check run requires a GitHub App *and* lists fine-grained PATs
among accepted token types. This contradiction is unresolved; do not build on
either reading. Exercise it against a scratch repository during Slice 3 and
record the result here. The design is insulated from the answer: surfaces are
capability-gated, so a connection that cannot create check runs falls back to
commit statuses.

| | Machine-user PAT | GitHub App |
|---|---|---|
| Seat cost | Consumes a paid org seat on Team/Enterprise | None |
| Identity | A person; dies with their access | Org-owned, bot badge |
| Token | Long-lived, manual rotation, ~1y max | Minted per delivery, 1h, down-scopable |
| SSO orgs | Per-org SSO authorization that silently expires | Sanctioned path |
| Revocation | We rotate | Customer uninstalls |
| Day-1 setup | Paste a token | Create App (manifest flow) + install |

**The App is optional, not load-bearing.** Nothing in the four invariants
requires one. A fine-grained PAT can resolve a commit to its PR, upsert the
sticky comment, and publish pending/final commit statuses — and the read needed
for resolution falls under the same "Pull requests" permission that commenting
already requires, so it costs no extra grant. Slices 0-3 run on a static token;
Slice 4 is a second implementation of a seam that GitLab forces us to have
anyway. What a PAT gives up is check runs (capability-gated, so it degrades to
commit statuses), a seat-free org-owned identity, and a per-installation rate
budget rather than 5000/hr shared across one machine user.

**Decision: the App is the intended long-term path for GitHub**, with the static
token path retained — it is required for GitLab/Bitbucket anyway and is a useful
quick-start. The App is reached via GitHub's app manifest flow, so the console
can create it programmatically and the operator only approves and installs;
this is how self-hosted Atlantis and Renovate handle being per-deployment rather
than multi-tenant. Document the App as the GitHub default before anyone builds
habits around PATs.

### Webhooks

| Capability | Needs | Wave |
|---|---|---|
| pending + final comment/status | Outbound only, incl. PR resolution lookups | v1 |
| Repo list / install lifecycle sync | Webhook, or lazy re-list | lazy first |
| Comment on PRs opened after the run; push-to-deploy | Webhooks | later, opt-in |

Outbound-only works behind NAT. Inbound webhooks require a publicly reachable
statedb, which many self-hosted deployments are not.

---

## Slice plan

**Slice 0 — evidence. No code.** **Done, with one shape outstanding.**

- Linkage: key names and population rules read from Pulumi source and written
  up in "Linkage: what the CLI actually sends".
- Secret-wrapper sentinel pinned against Pulumi SDK v3.259.0 and the recorded
  fixtures; see "The secret sentinel" under Invariant 2.
- Captured shapes live in `api-snapshots/pulumi/ci-metadata/` with a README
  covering reproduction: a plain local preview (**no** `ci.*` keys) and a
  `PULUMI_CI_SYSTEM` escape-hatch preview (all seven `ci.*` keys, values passed
  through verbatim). The escape-hatch capture is driven by a new
  `35-preview-ci-escape-hatch` command in the recorder's `entrypoint.sh`.
- **Outstanding:** a GitHub Actions `pull_request` run, where `ci.pr.number`
  and `ci.pr.headSHA` come from real detection and `git.head` is the synthetic
  merge commit. It needs an Actions runner and a repository with an open PR, so
  it is not reproducible from this repo's tooling. The design's claim about
  that shape rests on `sdk/go/common/util/ciutil/`, which states it directly.
  Capture it opportunistically; nothing in Slice 1 depends on it.

**Slice 1 — sanitization contract + derive + render + read endpoint.** **Done.**
The sanitization contract came first and is tested as a security surface
(hostile URNs, mention injection, bidi, oversized payloads, unrecognised secret
shapes). Then `ChangeReport`, the renderer, the report-oriented
`UpdateEventReader.stream` method, and
`GET /api/updates/{id}/report?format=markdown|json`. No egress, no new
dependencies, no new tables, ships value alone.
*Tests:* a golden file over a mixed run, asserted from live rows **and** from
the same run after `EventArchiveFolder` has folded it; adversarial sanitization
cases; endpoint authorization including cross-tenant refusal.

Two things this slice settled that the design had not:

- **The route needs its own entity loader.** `GET /api/updates/{id}/report`
  carries no org, project or stack, so `StackEntityLoader` — which reads that
  triple out of the path — cannot authorize it. `UpdateEntityLoader` resolves
  the update to its stack and applies the *same* read-visibility predicate and
  the same Cedar resource, so an update id is not a way to read a stack the
  principal cannot see. It denies with a plain 404: the request named no stack,
  and naming one would be the disclosure the predicate just prevented.
- **The console link is a parameter, not configuration.** The read endpoint
  builds it from the request's own base URI, which is by construction an
  address that works for the caller. A background publisher has no request to
  derive it from and will need a configured base URL; that belongs with the
  publisher in Slice 2, which is the thing that knows a comment outlives the
  request that caused it. `MarkdownReportRenderer` takes the link as an
  argument and stays pure either way.

**Slice 2 — targets, lifecycle, reconciliation, against `InMemoryVcs`.**
Bindings, `report_deliveries`, the predicate sweep, the start/terminal dispatch
points, supersession, and the `ReportPublisher` port. `InMemoryVcs` in
`test-support` models PR lookup *and* comment upsert with real semantics.
*Tests:* pending-on-start; all four terminal paths including cancellation;
out-of-order completion and retry; unverified-target rejection; PR-not-in-bound-
repo rejection; recreate-after-human-delete; concurrent workers. Nothing leaves
the process.

**Slice 3 — `GitHubPublisher`, static token.**
Real PR resolution, sticky comment, commit status, capability gating, and the
check-run evidence task. Credential storage modes land here.
*Tests:* recorded GitHub fixtures (`api-snapshots/github/`) replayed through a
stub server — auth, serialization, pagination, error mapping, rate-limit and
403 handling — with no network. A live smoke test against a scratch repo is
**opt-in, env-gated**, modelled on `modules/pulumi-cli-smoke`, out of
`./dev check`. Needs `./dev native-contract`.

**Slice 4 — App token source + connect flow.**
`InstallationTokenSource` (JWT -> installation token, down-scoped per delivery),
manifest-based "Connect GitHub" console flow, check-run surface if the evidence
task allows. Slices 1–3 untouched.

**Slice 5 — optional.** GitLab adapter, inbound webhooks, richer surfaces.

---

## Module placement

- `persistence/report/` — derive + render. Pure, no new dependencies.
- `persistence/vcs/` — bindings, deliveries, sweep, target resolution port.
- `modules/vcs/` (new) — host adapters and token sources, keeping REST client
  and RS256/JWT dependencies and their native-image reflection surface out of
  `persistence`.
- `console-api/` — report endpoint, connection and binding admin.

---

## Open questions

- **Comment body contract.** Given the values-never-emitted default: how many
  resource rows before collapsing, whether property paths group by resource or
  flatten, what the header line says for a failed or cancelled run.
- **Which kinds publish.** `preview` certainly. `update` on merge is useful but
  doubles comment volume. `refresh`, `destroy`?
- **Multi-stack PRs.** One comment per stack or one comment with a section per
  stack. Affects the sticky key and the supersession key together.
- **Status context naming.** Required checks match on context string, so the
  name is a compatibility surface once anyone gates on it.
- **Retention** of `report_deliveries`, and whether failures surface in the
  console operations view or only in logs.
