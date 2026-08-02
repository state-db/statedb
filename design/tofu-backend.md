# OpenTofu backend — implementation guide (Wave 3)

This document is the working spec for adding OpenTofu (and, for free, Terraform)
state support to state-db. It is written for the engineer/agent doing the
implementation: protocol contract first, settled design decisions second, then a
slice-by-slice task breakdown with acceptance criteria. Where a pattern already
exists in the Pulumi implementation, this doc names the exact file to copy from —
prefer copying a proven shape over inventing a new one.

Related docs: `architecture.md` (§ "Forward compat: OpenTofu"), `db-schema.md`
(the `workspaces` parent table), `implementation-slices-v2.md` (Slice 31 is the
bootstrap slice; the slices below extend it).

---

## Ground rules for the implementer

Read these before writing any code. They are repo-wide rules restated here
because violating them is the most likely failure mode.

1. **All build/test/run goes through the dev container.** Never run `mvn`,
   `java`, `tofu`, or `psql` on the host. Every command in this doc is shown in
   its runnable form: `./bin/in-container mvn -f modules/pom.xml ...`. If the
   wrapper says no container is running, stop and ask the user to start it.
2. **Never put slice numbers in source code.** No "Slice 31.3" in Javadoc,
   comments, commit messages, or test names. Describe the behavior instead
   ("lock verification on state writes"), not the planning artifact.
3. **ORM idioms are a hard rule** (see CLAUDE.md § ORM idioms). Default to
   Panache entities and static finders. `EntityManager` + native SQL is allowed
   only for the specific escape hatches this doc calls out (the lock upsert),
   and each use needs a Javadoc line naming the reason.
4. **Do not touch Pulumi tables, entities, or routes.** Tofu support is purely
   additive. If a change seems to require editing something under
   `persistence/stack/`, `persistence/update/`, or `pulumi-api/`, stop —
   the design is wrong or you misread it. (Two deliberate exceptions:
   `ProjectUpserter` is *reused* as-is, and `NameGrammar` validation is reused
   as-is. Reuse, not modification.)
5. **Protocol source of truth** is the OpenTofu client code, not this doc. If a
   real `tofu` run behaves differently from the contract below, the client wins;
   fix the doc in the same commit. The client lives at
   `github.com/opentofu/opentofu` → `internal/backend/remote-state/http/`
   (`backend.go` for config, `client.go` for the wire behavior). It is a
   near-verbatim fork of Terraform's, which is why Terraform compatibility
   falls out for free.
6. **One slice per PR-sized change.** Each slice below ends with its tests
   green and the manual verification passing. Don't start the next slice with
   the previous one half-done.

---

## Part 1 — The protocol contract

OpenTofu's `backend "http"` speaks five HTTP operations against URLs the user
pastes into their config. Unlike Pulumi there is **no discovery, no capability
negotiation, no update lifecycle, no leases, no events** — the CLI treats the
server as a dumb blob store with optional locking.

### 1.1 What the user configures

```hcl
terraform {
  backend "http" {
    address        = "https://statedb.example.com/tofu/acme/networking/prod"
    lock_address   = "https://statedb.example.com/tofu/acme/networking/prod"
    unlock_address = "https://statedb.example.com/tofu/acme/networking/prod"
    username       = "statedb"          # ignored by us, must be non-empty for basic auth
    password       = "<api token>"      # an existing state-db api_tokens token
  }
}
```

All three addresses point at the **same URL**; the HTTP method disambiguates.
Relevant client config defaults: `update_method=POST`, `lock_method=LOCK`,
`unlock_method=UNLOCK`, `retry_max=2` (client retries failed requests — handlers
must be idempotent). If `lock_address` is unset the client silently skips
locking; we cannot force it server-side, but our docs always show it set.

### 1.2 The five operations

All paths below are `/tofu/{org}/{project}/{workspace}`. `{workspace}` is the
Tofu-side state name (the HTTP backend has no named-workspace support — see
§1.5 — so this path segment is the *only* workspace identity).

| # | Request | Success | Failure modes we return |
|---|---------|---------|------------------------|
| 1 | `GET` | `200` + exact stored state bytes, `Content-Type: application/json`. **`404` when no state exists yet** (client treats 404/204 as "empty state" — this happens on every `tofu init` against a fresh workspace and must not be logged as an error) | `401` bad/missing credentials, `403` valid token but org denies |
| 2 | `POST` (body = full tfstate JSON; query `?ID=<lockID>` present **iff** the client holds a lock) | `200` empty body | `401`/`403`; `423` lock held but `ID` missing/mismatched; `409` serial/lineage safety rejection (§2.4); `400` body is not valid JSON |
| 3 | `DELETE` (no body, **no `?ID=` param** — the client never sends one on delete) | `200` empty body | `401`/`403`; `404` workspace does not exist; `423` if a lock is currently held |
| 4 | `LOCK` (body = lock-info JSON, §1.3) | `200` empty body — lock acquired | `423` + **current holder's stored lock-info JSON as the response body** (the client parses this to print "Lock Info: ID..., Who..., Created..." and the user feeds that ID to `force-unlock`); `401`/`403` |
| 5 | `UNLOCK` (body = lock-info JSON **or empty** — see §1.4) | `200` empty body | `423` + stored lock-info JSON when the body's `ID` doesn't match the held lock; `409` when no lock is held; `401`/`403` |

Notes that will bite you if skipped:

- **`LOCK` and `UNLOCK` are nonstandard HTTP verbs.** Quarkus REST supports
  custom methods via `@HttpMethod`. Define two annotations in `tofu-api`
  (`@LOCK`, `@UNLOCK`) — see §4.2. Verify early (in the bootstrap slice) that
  the whole chain (router, auth filter, resource dispatch) sees these verbs;
  this is the highest-risk unknown in the stack.
- **Return the stored bytes verbatim on GET.** Store the state as received and
  return it byte-identical. The client sends a `Content-MD5` header on POST
  (base64 MD5 of the body) and compares MD5s on read. Do **not** send a
  `Content-Md5` response header (omitting it is valid — the client then hashes
  the body itself); if we ever pretty-print or re-serialize the JSON, the
  hash chain still holds only because the client hashes what we actually
  return, but state *content* must round-trip exactly (Terraform diffs it).
  Column type is `JSONB` for console queries **plus** the original bytes —
  see §3 for how both are kept.
- **The `?ID=` param on POST is only present when locked.** A `POST` with no
  `ID` while a lock is held must be rejected with `423` (someone is writing
  without having locked). A `POST` with no `ID` while *no* lock is held must be
  accepted — `tofu state push` and lockless configs do this legitimately.

### 1.3 Lock-info JSON

The body of `LOCK` (and usually `UNLOCK`) is this exact shape, produced by the
client. Keys are capitalized; `Created` is RFC3339 with sub-second precision.

```json
{
  "ID": "d3b58bcc-56cf-04e9-1152-1c4c55e04cbd",
  "Operation": "OperationTypeApply",
  "Info": "",
  "Who": "sharath@host",
  "Version": "1.9.0",
  "Created": "2026-07-16T10:44:32.735103Z",
  "Path": ""
}
```

Store the whole document verbatim (JSONB) and index/compare only `ID`. On
conflict responses, return the **stored** document, not a reconstruction —
the client json-decodes it and any missing field degrades its error message.

### 1.4 Unlock and force-unlock

Two distinct client behaviors, one endpoint:

- **Normal unlock** (end of `apply`): the client sends the same lock-info JSON
  it sent on LOCK. Match on `ID`: if it equals the held lock's ID, delete the
  lock row and return `200`. If it differs, return `423` with the stored
  lock-info.
- **`tofu force-unlock <id>`**: the client process never called LOCK, so its
  saved lock-info is nil and it sends an UNLOCK with an **empty body**. Server
  behavior: if the body is empty or unparseable as lock-info, delete whatever
  lock is held and return `200` (also `200` if no lock is held — force-unlock
  must be idempotent). This asymmetry is deliberate: an empty body can only be
  produced by a human running force-unlock, which is already the "I know what
  I'm doing" path.

### 1.5 No named workspaces

The HTTP backend only supports the single `default` workspace; `tofu workspace
new` fails client-side with "workspaces not supported". Consequence: **every
distinct state gets its own URL**, and our `{workspace}` path segment plays the
role Tofu workspaces play elsewhere. Do not build anything that enumerates
workspaces over the Tofu protocol — there is no such call. Enumeration is a
console feature only.

### 1.6 The state blob (tfstate v4)

What arrives on POST — needed for the console parser, not for the backend
(the backend treats it as opaque apart from `serial`/`lineage` extraction):

```json
{
  "version": 4,
  "terraform_version": "1.9.0",
  "serial": 7,
  "lineage": "3f8a1c2e-...-uuid",
  "outputs": {
    "vpc_id": { "value": "vpc-123", "type": "string" },
    "db_password": { "value": "hunter2", "type": "string", "sensitive": true }
  },
  "resources": [
    {
      "module": "module.network",
      "mode": "managed",
      "type": "aws_vpc",
      "name": "main",
      "provider": "provider[\"registry.opentofu.org/hashicorp/aws\"]",
      "instances": [
        {
          "index_key": 0,
          "schema_version": 1,
          "attributes": { "id": "vpc-123", "cidr_block": "10.0.0.0/16" },
          "sensitive_attributes": [],
          "dependencies": ["aws_internet_gateway.gw"]
        }
      ]
    }
  ],
  "check_results": null
}
```

Field notes: `module` is absent for root-module resources; `mode` is
`"managed"` or `"data"`; `index_key` (int or string) is present only for
`count`/`for_each` instances; OpenTofu still writes the field name
`terraform_version`. `serial` is a monotonically increasing write counter
managed by the client; `lineage` is a UUID assigned when a state is first
created and never changes for the life of that state.

### 1.7 Client-side encrypted state (OpenTofu ≥ 1.7)

If the user enables OpenTofu state encryption, the POSTed blob is an envelope,
not tfstate:

```json
{ "serial": 7, "lineage": "…", "meta": { … }, "encrypted_data": "base64…", "encryption_version": "v0" }
```

Detection rule: top-level key `encrypted_data` present ⇒ encrypted. The
**backend stores and serves it like any other blob** (serial/lineage are still
plaintext in the envelope, so §2.4 safety checks still work). The **console**
must detect it and render an "encrypted state" notice instead of attempting to
parse resources. Never log or error on the envelope.

### 1.8 Terraform compatibility

Terraform's http backend (≥ 0.13) is wire-identical. No code differences —
just include one Terraform version in the nightly smoke matrix (§6.3) and say
"works with Terraform" in the README once the matrix proves it.

---

## Part 2 — Settled design decisions

These were decided during design review. Do not re-litigate them during
implementation; if one proves unworkable, stop and surface it.

### 2.1 URL scheme: one URL, verbs disambiguate

`/tofu/{org}/{project}/{workspace}` for address, lock_address, and
unlock_address. Users copy one URL into three fields. All three path segments
follow the existing name grammar (`NameGrammar.java`,
`^[A-Za-z0-9._-]{1,100}$`) — reject violations with `400` before touching the
DB, exactly like the Pulumi paths do, because names become Cedar resource ids
joined with `/`.

### 2.2 Auth: Basic (password = token) and Bearer

Accept two forms, both resolving through the **existing** token machinery
(`ApiTokenHasher` → `ApiTokenFinder.findValidByHash`, same as
`TokenAuthFilter`):

- `Authorization: Basic base64(user:pass)` — `pass` is the raw api token,
  `user` is ignored (must be non-empty per HTTP, docs say `statedb`). This
  works on every Tofu/Terraform version and is the documented default.
- `Authorization: Bearer <token>` — for users on versions with `headers`
  support who prefer explicit headers.

Do **not** accept the Pulumi `token <tok>` scheme here; keeping the schemes
disjoint per protocol surface makes audit logs unambiguous. The filter is a new
class in `tofu-api` (§4.2), gating only paths under `/tofu/`.

### 2.3 Storage: append-only full snapshots

Every accepted POST inserts a new `tofu_states` row. No journaling, no deltas,
no compaction — Tofu always sends the complete state, and blobs are small
(KBs to low MBs). "Current state" = the row with the highest `id` (UUIDv7 is
time-ordered) per workspace; history for the console is the row list.
Retention (keep last N) is a follow-up slice, not v1.

### 2.4 Serial/lineage safety checks (state safety is the product)

On POST, after JSON parsing, extract top-level `serial` (number) and `lineage`
(string) — these exist in both plaintext and encrypted envelopes. Against the
current head (if one exists):

- `lineage` differs from head's lineage → `409`, body explains the mismatch
  and names the escape hatch.
- `serial` < head's serial → `409` likewise.
- `serial` == head's serial **and** bytes differ → `409` (two writers raced
  with locking disabled).
- `serial` == head's serial and bytes identical → `200`, insert nothing
  (idempotent retry — remember `retry_max=2`).

Known consequence, accepted by design: `tofu state push -force` of an *older*
state is refused (the client's `-force` flag never reaches the server). The
escape hatch is `DELETE` the state first (`tofu init` a scratch dir pointing
at the URL, or the console once delete lands there), then push — a fresh
workspace has no head, so all checks pass. Put this in the error message body
so users are never stuck.

### 2.5 Workspace auto-creation

There is no "create workspace" call in the protocol. Rules:

- `LOCK` and `POST` **resolve-or-create**: the org must exist (never
  auto-create orgs — `404` if unknown, matching Pulumi behavior); the project
  is upserted via the existing `ProjectUpserter`; the `workspaces` parent row
  (`kind='tofu'`) and `tofu_workspaces` row are created if absent, inside the
  same transaction.
- `GET`, `DELETE`, `UNLOCK` **resolve only** — unknown workspace ⇒ `404`
  (GET's "no state" 404 and "no workspace" 404 are indistinguishable to the
  client, which is fine).

### 2.6 No shared abstraction with the Pulumi update lifecycle

Restating the architecture.md decision because it constrains this work: the
Pulumi update/journal/event machinery is **not** generalized to cover Tofu.
The only shared pieces are: the `workspaces` parent table (identity for
cross-tool listing and the Free-Edition cap), `projects`/`organizations`,
the token/auth machinery, and the Cedar PDP. Everything else is parallel,
Tofu-specific, and smaller.

### 2.7 Locking maps to `tofu_locks`, not Pulumi's lease system

Pulumi's `stack_locks`/lease/renew machinery models a long-running update with
heartbeats. Tofu's lock is a simple mutex with no expiry and no renewal — the
client holds it for the duration of one command and a crashed client is
recovered by human `force-unlock`. Do not add TTL/expiry logic; that would
diverge from what every other Tofu backend does and surprise users.

---

## Part 3 — Schema

One migration. Use the next free version number when you start (V014 at time
of writing — check `modules/persistence/src/main/resources/db/migration/`).
Follow the house style visible in `V002__stack_tree.sql`: comments explain
invariants, FKs are explicit, no DB-generated UUIDs (app supplies UUIDv7).

```sql
-- OpenTofu state tables. The tofu_workspaces extension row joins 1:1 to the
-- type-erased workspaces parent (kind='tofu'); Pulumi tables are untouched.
-- See design/tofu-backend.md for the protocol these back.

CREATE TABLE tofu_workspaces (
    workspace_id UUID        PRIMARY KEY,
    org_id       UUID        NOT NULL,
    project_id   UUID        NOT NULL,
    name         TEXT        NOT NULL CHECK (name ~ '^[A-Za-z0-9._-]{1,100}$'),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name),
    -- Composite FKs pin workspace and project to the same org (tenant
    -- isolation, same pattern as stacks).
    FOREIGN KEY (workspace_id, org_id) REFERENCES workspaces(id, org_id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, org_id)   REFERENCES projects(id, org_id)   ON DELETE CASCADE
);

-- Append-only full state snapshots. Head = max(id) per workspace (UUIDv7 is
-- time-ordered). blob holds the bytes exactly as received; blob_json is the
-- parsed copy for console queries and is NULL when the state is client-side
-- encrypted (OpenTofu 1.7+ envelope).
CREATE TABLE tofu_states (
    id           UUID        PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES tofu_workspaces(workspace_id) ON DELETE CASCADE,
    serial       BIGINT      NOT NULL,
    lineage      TEXT        NOT NULL,
    blob         BYTEA       NOT NULL,
    blob_json    JSONB,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tofu_states_workspace ON tofu_states (workspace_id, id DESC);

-- At most one lock per workspace; PK enforces it. info is the client's
-- lock-info JSON stored verbatim (returned on 423 conflicts).
CREATE TABLE tofu_locks (
    workspace_id UUID        NOT NULL PRIMARY KEY REFERENCES tofu_workspaces(workspace_id) ON DELETE CASCADE,
    lock_id      TEXT        NOT NULL,
    info         JSONB       NOT NULL,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Entity mapping (`modules/persistence`, new package
`app/statedb/persistence/tofu/`):

- `TofuWorkspace extends TimestampedEntity`... **no** — its PK is
  `workspace_id` (shared with the parent), not a self-generated id, so it does
  not fit `BaseEntity`. Extend `PanacheEntityBase` directly with `@Id UUID
  workspaceId` (the `OrgMember` composite-PK precedent). Add the lazy
  `@ManyToOne` navigations to `Workspace` and `Project` alongside the raw UUID
  columns per the house relationship-navigation idiom.
- `TofuState extends PanacheEntityBase` with app-generated UUIDv7 id — it's
  append-only but also can't use `BaseEntity` if `BaseEntity` assumes its own
  id generation fits; check `BaseEntity` first and extend it if it fits
  (UUIDv7 + created_at is exactly its shape — it should fit).
- `TofuLock extends PanacheEntityBase`, `@Id UUID workspaceId`.

---

## Part 4 — Module layout

### 4.1 New Maven module `modules/tofu-api`

Mirror `pulumi-api`'s pom (Quarkus REST + dependency on
`state-db-persistence`). Register it in `modules/pom.xml` `<modules>` and add
it as a dependency of `modules/app/pom.xml` (grep for `state-db-pulumi-api`
there and mirror the block). Package root: `app.statedb.tofuapi`.

### 4.2 Classes and responsibilities

```
tofu-api/src/main/java/app/statedb/tofuapi/
  http/LOCK.java              @HttpMethod("LOCK")  custom-verb annotation
  http/UNLOCK.java            @HttpMethod("UNLOCK")
  auth/TofuAuthFilter.java    ContainerRequestFilter, @Priority(AUTHENTICATION), @Blocking
  state/TofuStateResource.java   the five endpoints, @Path("/tofu/{org}/{project}/{workspace}")
  state/LockInfo.java            record mirroring §1.3 (Jackson, capitalized keys via @JsonProperty)
  error/…ExceptionMapper.java    423/409 mappers (see below)
```

Custom verb annotation shape (this is the part least likely to be guessed
right, so here it is verbatim):

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod("LOCK")
public @interface LOCK {}
```

`TofuAuthFilter`: copy the structure of
`pulumi-api/.../auth/TokenAuthFilter.java` (same non-`@PreMatching` +
`@Blocking` rationale — keep that comment's substance), gate on path prefix
`/tofu/`, parse Basic and Bearer per §2.2, and set the same
`CurrentPrincipal`. Default-deny under the prefix, no public paths.

`TofuStateResource`: five methods (`@GET`, `@POST`, `@DELETE`, `@LOCK`,
`@UNLOCK`). Keep it thin — resolution, request/response mapping, and
delegation only. Business logic lives in persistence services (§4.3). POST
reads the raw body as `byte[]` (`@Consumes(MediaType.WILDCARD)` — do not let
Jackson deserialize the state; we need the exact bytes).

Exception mappers: follow the existing pattern
(`StackLockedExceptionMapper` etc. in `pulumi-api`). New exceptions →
responses: `TofuLockConflictException` (carries the stored lock-info JSON) →
`423` with that JSON body; `TofuStateSafetyException` → `409` with a
plain-text explanation including the escape hatch (§2.4).

### 4.3 Persistence services (package `app.statedb.persistence.tofu`)

Named per the house command/query naming rules:

- `TofuWorkspaceResolver` — resolves `(org, project, workspace)` →
  workspace row; one variant that creates (§2.5, `@Transactional`, reuses
  `ProjectUpserter`), one that only reads. The read path is a 3-table JOIN like
  `StackRefResolver`; start with Panache `find` + `join fetch`, and only drop
  to the `StackRefResolver`-style EntityManager projection if profiling ever
  says so — do not pre-optimize.
- `TofuStateWriter` — `@Transactional`; runs the §2.4 safety checks against
  the head, verifies the lock precondition (§1.2 row 2), inserts the
  `tofu_states` row. Lock the workspace row (`SELECT … FOR UPDATE` via
  Panache `LockModeType.PESSIMISTIC_WRITE` on the `TofuWorkspace` find) so
  check-then-insert is race-free across pods.
- `TofuStateReader` — head lookup for GET (order by id desc, limit 1).
- `TofuLockService` — acquire (insert; on PK conflict load the holder and
  throw `TofuLockConflictException` with its info), release (match `ID` per
  §1.4), force-release. Acquire uses `INSERT … ON CONFLICT DO NOTHING` via
  `createNativeQuery` — this is a sanctioned escape hatch (same justification
  as `StackCreator#upsertProject`); say so in the Javadoc.
- `TofuWorkspaceDeleter` — `@Transactional`; refuses when a lock is held
  (`423`), then deletes the `workspaces` parent row and lets the FK cascade
  take the extension row, states, and lock.

### 4.4 Authorization (Cedar)

Mirror the Pulumi wiring in `persistence/authz/`: a
`TofuWorkspaceEntityLoader` sibling of `StackEntityLoader` (resolves the path
triple to org-scoped resource ids), and new actions in the policy set —
`tofu:readState`, `tofu:writeState`, `tofu:deleteState`, `tofu:lock`,
`tofu:unlock`. Role mapping matches the Pulumi analogues: `viewer` gets
`readState`; `member` and `owner` get everything; org mismatch is a `404` (not
`403`) exactly like the stack loader, so cross-org probing can't distinguish
"exists" from "denied". Wire enforcement through the same `@Authorize`
interceptor the Pulumi resources use.

---

## Part 5 — Console

Kept deliberately thin here; it gets its own slice and follows Slice 29's page
shell. Backend prerequisites the console needs from this work:

- `/api/workspaces` (Slice 28) already returns mixed kinds — Tofu rows appear
  automatically once `kind='tofu'` rows exist. Verify, don't build.
- New console-api reads (in `console-api`, backed by finders in
  `persistence/tofu/`): workspace detail (head serial, updated-at, resource
  count), state history (the `tofu_states` rows), parsed resources + outputs
  for the head.
- `TofuStateParser` in `persistence/tofu/` — parses §1.6 into records
  (module path, mode, type, name, provider trimmed to the short name,
  instance count, attributes). Sensitive handling: outputs with
  `"sensitive": true` and attributes named in `sensitive_attributes` are
  masked to `"<sensitive>"` **server-side**; raw values never leave the API.
  Encrypted envelope (§1.7) → return an "encrypted" marker, parse nothing.

---

## Part 6 — Testing strategy

Three tiers, mirroring the Pulumi approach at smaller scale. No
session-recorder/replay harness for Tofu — the protocol is five operations;
the live smoke tier covers the real client cheaply.

### 6.1 Contract tests (REST-assured, in `tofu-api` `src/test`)

Table-driven where sensible. The must-have cases, each traceable to Part 1/2:

- GET unknown workspace → 404; GET after POST → byte-identical body.
- POST creates workspace+project on first write; org unknown → 404.
- POST with lock held and correct `?ID=` → 200; wrong/missing `ID` → 423.
- POST without lock and no `ID` → 200 (lockless mode is legal).
- Safety: lower serial → 409; same serial + different bytes → 409; same
  serial + same bytes → 200 idempotent no-op (row count unchanged); lineage
  mismatch → 409; every 409 body mentions the delete-then-push escape hatch.
- LOCK on unlocked → 200; second LOCK → 423 and body is the *first* client's
  lock-info verbatim.
- UNLOCK matching ID → 200; mismatched ID → 423; empty body → 200 and lock
  gone (force-unlock); empty body with no lock held → 200.
- DELETE with lock held → 423; DELETE → GET now 404, workspace gone, Pulumi
  tables untouched (count assertion via Panache).
- Auth matrix: no header → 401; Basic with valid token → 200; Bearer with
  valid token → 200; Pulumi `token` scheme → 401; viewer role can GET but
  POST → 403 (or 404 per the loader convention — assert whichever §4.4 wiring
  produces, consistently).
- Name grammar: `{workspace}` with a `/`-encoded or illegal char → 400.
- Encrypted envelope POST → 200, `blob_json` NULL, GET round-trips bytes.

### 6.2 CLI smoke module `modules/tofu-cli-smoke`

Sibling of `pulumi-cli-smoke` (copy `PulumiSmokeBase`'s structure: start the
app, provision an org + token, drive the real CLI in a temp dir). Scenarios:

- **Lifecycle**: `tofu init` → `apply` (a couple of `terraform_data` /
  `random_pet` resources — no cloud creds) → `apply` a change → `destroy`.
  Assert state rows and serials between steps.
- **Locking**: hold a lock (start an `apply` against a resource with a slow
  provisioner, or acquire via raw LOCK), run a second `apply`, assert it
  fails printing the first holder's lock info; `tofu force-unlock` the ID;
  assert a subsequent `apply` succeeds.
- **State push safety**: `tofu state pull`, edit serial down, `state push
  -force` → assert the CLI surfaces our 409 text.

Pin the `tofu` version the same way `pulumi-cli-smoke` pins the Pulumi CLI
(see that module's container/setup for the mechanism — reuse it).

### 6.3 Nightly matrix

Extend the existing nightly CLI matrix workflow (`.github/workflows/`, added
with the Pulumi matrix) with a Tofu axis: latest patch of each supported minor
(1.6 through current) plus one Terraform version (latest 1.x). Same
green/red reporting as the Pulumi rows.

---

## Part 7 — Slice breakdown

Slice 31 in `implementation-slices-v2.md` (module bootstrap, 501 stubs) is the
entry point and stays as written, with one addition folded into it: the
custom-verb spike below. Each slice = green tests + manual verification before
moving on.

### Slice 31 (amended) · Bootstrap + custom-verb spike

As specified in implementation-slices-v2.md, **plus**: implement the `@LOCK`
and `@UNLOCK` annotations and have the 501 stub resource include LOCK/UNLOCK
methods, proving Quarkus routes nonstandard verbs through the filter chain to
a resource method. This de-risks the stack's biggest unknown for the cost of
two annotations.

**Manual verification**
```
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
curl -X LOCK http://localhost:8080/tofu/o/p/w   # expect 501, not 405
```

### Slice 31.1 · Schema + entities

**Scope.** The Part 3 migration and the four entities/records in
`app.statedb.persistence.tofu`. No endpoints change.

**Tests.** Migration applies on a fresh DB (existing Flyway test setup);
entity round-trip persists and reads back; cascade delete from `workspaces`
removes extension row, states, and lock; `UNIQUE (project_id, name)` and the
name-grammar CHECK reject bad rows.

**Manual verification.** `./bin/in-container mvn -f modules/pom.xml -pl persistence -am test`

### Slice 31.2 · Auth filter + workspace resolution + Cedar actions

**Scope.** `TofuAuthFilter` (§2.2), `TofuWorkspaceResolver` (§2.5),
`TofuWorkspaceEntityLoader` + the five actions and role policies (§4.4).
Stubs now return 501 only *behind* auth.

**Tests.** Auth matrix from §6.1; resolve-or-create idempotency (two calls,
one row); cross-org resolution → 404; viewer/member/owner action matrix.

**Manual verification.** `curl` with Basic auth from a real minted token
reaches the 501; without it, 401.

### Slice 31.3 · State GET/POST/DELETE

**Scope.** `TofuStateReader`, `TofuStateWriter` (safety checks §2.4,
pessimistic workspace lock, idempotent retry), `TofuWorkspaceDeleter`,
the three resource methods, `TofuStateSafetyException` + mapper. Lock
*verification* is included only as "no lock may be held" (the lock table
exists but nothing writes it yet, so the `?ID=` path trivially passes —
write the check against the table anyway so 31.4 doesn't reopen this class).

**Tests.** §6.1 rows for GET/POST/DELETE, safety matrix, encrypted envelope,
byte-identical round-trip.

**Manual verification.** Real CLI, lockless config (no `lock_address`):
`tofu init && tofu apply && tofu destroy` against dev mode succeeds; the
`tofu_states` table shows one row per write with ascending serials.

### Slice 31.4 · LOCK/UNLOCK

**Scope.** `TofuLockService`, the two resource methods, `LockInfo` record,
`TofuLockConflictException` + 423 mapper, `?ID=` enforcement in the writer,
DELETE's held-lock refusal.

**Tests.** §6.1 lock rows: conflict body verbatim, unlock ID match, empty-body
force-unlock idempotency, POST `?ID=` matrix.

**Manual verification.** Two terminals, same backend URL with lock_address
set: second `apply` prints the first's lock info; `tofu force-unlock <id>`
recovers.

### Slice 31.5 · `tofu-cli-smoke` + nightly matrix

**Scope.** The §6.2 module and §6.3 workflow extension. Also the README
gains a "Using with OpenTofu/Terraform" section showing the §1.1 config
block.

**Tests.** The module *is* the test.

**Manual verification.** `./bin/in-container mvn -f modules/pom.xml -pl tofu-cli-smoke -am verify`
(or the module's documented invocation — match how `pulumi-cli-smoke` runs).

### Slice 31.6 · Console drilldown

**Scope.** `TofuStateParser` (§5, sensitive masking, encrypted marker),
console-api endpoints for workspace detail / history / resources+outputs,
web routes following the Slice 29 shell, `kind` badge per console-ux.md.

**Tests.** Parser: root vs module resources, count/for_each `index_key`,
data-mode resources, sensitive masking (assert the raw value appears nowhere
in the response body), encrypted marker. API: cross-org 404, paging.

**Manual verification.** Browser shows resources and outputs of a smoked
workspace; sensitive output renders masked.

### Slice 31.7 · Retention + Free-Edition cap accounting

**Scope.** Keep-last-N pruning of `tofu_states` per workspace (piggyback on
the Phase 7 retention scheduler patterns); confirm the Slice 35 active-count
implementation counts `workspaces` rows kind-agnostically — if Slice 35
landed Pulumi-only, fix its counter here; if it hasn't landed, leave a note
in its slice text instead.

**Tests.** Pruning keeps exactly N and never the head; cap counting includes
a `kind='tofu'` workspace.

---

## Appendix — quick reference

**User-facing config** — §1.1. **Lock-info JSON** — §1.3. **tfstate v4** —
§1.6. **Decision log** — Part 2. When the OpenTofu client and this doc
disagree, the client wins (Ground rule 5).
