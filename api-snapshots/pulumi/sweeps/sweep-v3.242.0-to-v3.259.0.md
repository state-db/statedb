# Pulumi compat sweep: v3.242.0 → v3.259.0

Generated: 2026-08-23T03:51:53Z

## Pin audit

| Pin | Value | Status |
|---|---|---|
| recorder CLI (`tools/pulumi/recorder/Dockerfile`) | 3.242.0 | baseline |
| verify SDK (`tools/pulumi/verify/go.mod`) | 3.242.0 | OK |
| release matrix (`.github/workflows/native.yml`) | 3.234.0,3.242.0 | OK (baseline in matrix); target 3.259.0 not yet in matrix |

Prunable committed snapshots (neither baseline nor target; regenerable from pinned refs): v3.233.0,v3.234.0 v3.253.0

## Endpoint diff (source-level, scoped)

# API Comparison: v3.242.0 → v3.259.0

| | Count |
|---|---|
| Endpoints in v3.242.0 | 89 |
| Endpoints in v3.259.0 | 89 |
| Added | 0 |
| Removed | 0 |

No endpoint changes between these versions.

Full inventory: `compare-v3.242.0-v3.259.0.md` (published next to this report; otherwise in the sweep scratch dir).

## Trial capture (recorder, pulumi 3.259.0)

Status: completed

fixture diff: 9 commands with call-sequence changes; 57 calls changed, 11 added, 3 removed, 365 unchanged

Full masked diff: `tools/pulumi/compat/.sweep/v3.259.0/fixtures-diff.md`

### Catalog diff

```diff
--- /Users/sharath/code/statedb/api-snapshots/pulumi/catalog.md	2026-07-27 21:06:43
+++ /Users/sharath/code/statedb/tools/pulumi/compat/.sweep/v3.259.0/snapshots/catalog.md	2026-08-22 23:51:53
@@ -11,17 +11,17 @@
 | # | Method | Path | Calls | Commands |
 |---|--------|------|-------|----------|
 | 1 | `GET` | `/api/user` | 1 | login |
-| 2 | `GET` | `/api/capabilities` | 57 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
-| 3 | `GET` | `/api/user/organizations/default` | 33 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
-| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 48 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-init-for-cancel, cancel, stack-rm-after-cancel |
+| 2 | `GET` | `/api/capabilities` | 56 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
+| 3 | `GET` | `/api/user/organizations/default` | 32 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
+| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 47 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-init-for-cancel, cancel, stack-rm-after-cancel |
 | 5 | `POST` | `/api/stacks/{org}/{project}` | 3 | stack-init, stack-init, stack-init-for-cancel |
-| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 6 | up, up-after-destroy, up |
-| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
-| 8 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 25 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, up, stack-output, stack-get, stack-graph, destroy, change-secrets-provider |
-| 9 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
-| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
-| 11 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 102 | up, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
-| 12 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 63 | up, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
+| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 71 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
+| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 6 | up, up-after-destroy, up |
+| 8 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
+| 9 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 25 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, up, stack-output, stack-get, stack-graph, destroy, change-secrets-provider |
+| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
+| 11 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
+| 12 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 105 | up, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
 | 13 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
 | 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/batch-decrypt` | 16 | preview, refresh, destroy, destroy-after-rebuild, stack-output, stack-get, stack-graph, destroy |
 | 15 | `POST` | `/api/stacks/{org}/{project}/{stack}/refresh` | 2 | refresh |
@@ -66,6 +66,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/update
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -111,6 +112,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/preview
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -128,6 +130,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
@@ -200,6 +203,7 @@
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/destroy
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -216,6 +220,7 @@
 GET /api/stacks/{org}/{project}/{stack}
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
+PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
@@ -257,6 +262,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/update
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -306,6 +312,7 @@
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/destroy
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -322,6 +329,7 @@
 GET /api/stacks/{org}/{project}/{stack}
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
+PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
@@ -338,9 +346,9 @@
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
-POST /api/stacks/{org}/{project}/{stack}/encrypt
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
@@ -372,6 +380,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/update
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -488,9 +497,6 @@
 ### `stack-unselect`
 
 ```
-GET /api/capabilities
-GET /api/user/organizations/default
-GET /api/stacks/{org}/{project}/{stack}
 ```
 
 ### `stack-graph`
@@ -536,6 +542,7 @@
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/destroy
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -552,6 +559,7 @@
 GET /api/stacks/{org}/{project}/{stack}
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
+PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/encrypt
@@ -672,7 +680,7 @@
 
 ### 2. `GET /api/capabilities`
 
-**Calls:** 57 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `21-stack-unselect` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
+**Calls:** 56 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
 
 **Response** (`200`):
 
@@ -691,7 +699,7 @@
 
 ### 3. `GET /api/user/organizations/default`
 
-**Calls:** 33 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `21-stack-unselect` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
+**Calls:** 32 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
 
 **Response** (`200`):
 
@@ -708,7 +716,7 @@
 
 ### 4. `GET /api/stacks/{org}/{project}/{stack}`
 
-**Calls:** 48 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `21-stack-unselect` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
+**Calls:** 47 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`
 
 **Response** (`200`):
 
@@ -767,8 +775,32 @@
 
 ---
 
-### 6. `POST /api/stacks/{org}/{project}/{stack}/update`
+### 6. `POST /api/stacks/{org}/{project}/{stack}/encrypt`
+
+**Calls:** 71 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`
+
+**Request body:**
+
+```json
+{
+  "plaintext": "[secret]"
+}
+```
+
+_Replay request body preserved in `body_b64` (24 bytes)._
+
+**Response** (`200`):
+
+```json
+{
+  "ciphertext": "[secret]"
+}
+```
 
+---
+
+### 7. `POST /api/stacks/{org}/{project}/{stack}/update`
+
 **Calls:** 6 &nbsp;·&nbsp; **Commands:** `03-up` `09a-up-after-destroy` `12-up`
 
 **Request body:**
```

## Follow-ups when adopting v3.259.0

1. Review the diffs above; adjust stubs/state-db if the protocol moved.
2. Bump `ARG PULUMI_VERSION` in `tools/pulumi/recorder/Dockerfile`, re-run `make pulumi-recorder-discover`, review + commit fixtures/catalog.
3. Bump `github.com/pulumi/pulumi/sdk/v3` in `tools/pulumi/verify/go.mod` to v3.259.0 (lockstep rule).
4. Update the release matrix in `.github/workflows/native.yml` (keep oldest-supported + new pin).
5. Run the replay/smoke/fault tiers before committing pin bumps.
6. Delete superseded snapshot dirs under `api-snapshots/pulumi/` (see pin audit).
