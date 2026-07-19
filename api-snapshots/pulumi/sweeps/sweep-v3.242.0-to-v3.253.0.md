# Pulumi compat sweep: v3.242.0 → v3.253.0

Generated: 2026-07-19T03:51:51Z

## Pin audit

| Pin | Value | Status |
|---|---|---|
| recorder CLI (`tools/pulumi/recorder/Dockerfile`) | 3.242.0 | baseline |
| verify SDK (`tools/pulumi/verify/go.mod`) | 3.242.0 | OK |
| nightly matrix (`.github/workflows/nightly.yml`) | 3.234.0,3.242.0 | OK (baseline in matrix); target 3.253.0 not yet in matrix |

Prunable committed snapshots (neither baseline nor target; regenerable from pinned refs): v3.233.0,v3.234.0

## Endpoint diff (source-level, scoped)

# API Comparison: v3.242.0 → v3.253.0

| | Count |
|---|---|
| Endpoints in v3.242.0 | 89 |
| Endpoints in v3.253.0 | 89 |
| Added | 0 |
| Removed | 0 |

No endpoint changes between these versions.

Full inventory: `compare-v3.242.0-v3.253.0.md` (published next to this report; otherwise in the sweep scratch dir).

## Trial capture (recorder, pulumi 3.253.0)

Status: completed

fixture diff: 7 commands with call-sequence changes; 5 calls changed, 7 added, 0 removed, 319 unchanged

Full masked diff: `tools/pulumi/compat/.sweep/v3.253.0/fixtures-diff.md`

### Catalog diff

```diff
--- /Users/sharath/code/state-db/api-snapshots/pulumi/catalog.md	2026-07-12 22:20:41
+++ /Users/sharath/code/state-db/tools/pulumi/compat/.sweep/v3.253.0/snapshots/catalog.md	2026-07-18 23:51:50
@@ -11,17 +11,17 @@
 | # | Method | Path | Calls | Commands |
 |---|--------|------|-------|----------|
 | 1 | `GET` | `/api/user` | 1 | login |
-| 2 | `GET` | `/api/capabilities` | 45 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm |
+| 2 | `GET` | `/api/capabilities` | 46 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm |
 | 3 | `GET` | `/api/user/organizations/default` | 28 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm |
 | 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 38 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-unselect, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename |
 | 5 | `POST` | `/api/stacks/{org}/{project}` | 2 | stack-init, stack-init |
-| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 4 | up, up |
-| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 11 | up, preview, refresh, destroy, up, destroy |
-| 8 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 20 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy, up, stack-output, stack-get, stack-graph, destroy, change-secrets-provider |
-| 9 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 11 | up, preview, refresh, destroy, up, destroy |
-| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 11 | up, preview, refresh, destroy, up, destroy |
-| 11 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 73 | up, refresh, destroy, up, destroy |
-| 12 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 45 | up, refresh, destroy, up, destroy |
+| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 51 | up, preview, refresh, destroy, up, destroy |
+| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 4 | up, up |
+| 8 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 11 | up, preview, refresh, destroy, up, destroy |
+| 9 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 20 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy, up, stack-output, stack-get, stack-graph, destroy, change-secrets-provider |
+| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 11 | up, preview, refresh, destroy, up, destroy |
+| 11 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 11 | up, preview, refresh, destroy, up, destroy |
+| 12 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 73 | up, refresh, destroy, up, destroy |
 | 13 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
 | 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/batch-decrypt` | 13 | preview, refresh, destroy, stack-output, stack-get, stack-graph, destroy |
 | 15 | `POST` | `/api/stacks/{org}/{project}/{stack}/refresh` | 2 | refresh |
@@ -65,6 +65,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/update
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -110,6 +111,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/preview
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -127,6 +129,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
@@ -199,6 +202,7 @@
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/destroy
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -274,6 +278,7 @@
 GET /api/user/organizations/default
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/capabilities
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/update
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -438,6 +443,7 @@
 GET /api/stacks/{org}/{project}/{stack}/export
 GET /api/capabilities
 POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
+POST /api/stacks/{org}/{project}/{stack}/encrypt
 POST /api/stacks/{org}/{project}/{stack}/destroy
 POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
 GET /api/stacks/{org}/{project}/{stack}/export
@@ -487,6 +493,7 @@
 GET /api/stacks/{org}/{project}/{stack}
 GET /api/stacks/{org}/{project}/{stack}/export
 POST /api/stacks/{org}/{project}/{stack}/import
+GET /api/capabilities
 GET /api/stacks/{org}/{project}/{stack}/update
 ```
 
@@ -545,7 +552,7 @@
 
 ### 2. `GET /api/capabilities`
 
-**Calls:** 45 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `21-stack-unselect` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm`
+**Calls:** 46 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `21-stack-unselect` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm`
 
 **Response** (`200`):
 
@@ -640,8 +647,32 @@
 
 ---
 
-### 6. `POST /api/stacks/{org}/{project}/{stack}/update`
+### 6. `POST /api/stacks/{org}/{project}/{stack}/encrypt`
+
+**Calls:** 51 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `12-up` `26-destroy`
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
 
+```json
+{
+  "ciphertext": "[secret]"
+}
+```
+
+---
+
+### 7. `POST /api/stacks/{org}/{project}/{stack}/update`
+
 **Calls:** 4 &nbsp;·&nbsp; **Commands:** `03-up` `12-up`
 
 **Request body:**
@@ -664,7 +695,7 @@
       "pulumi.flag.parallel": "set",
       "pulumi.flag.yes": "true",
       "pulumi.os": "linux",
-      "pulumi.version": "v3.242.0",
+      "pulumi.version": "v3.253.0",
       "runtime.name": "yaml",
       "stack.environments": "[]",
       "updatePlan": "false"
@@ -700,7 +731,7 @@
 
 ---
 
-### 7. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`
+### 8. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`
 
 **Calls:** 11 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `12-up` `26-destroy`
 
@@ -732,7 +763,7 @@
 
 ---
 
-### 8. `GET /api/stacks/{org}/{project}/{stack}/export`
+### 9. `GET /api/stacks/{org}/{project}/{stack}/export`
 
 **Calls:** 20 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `12-up` `13-stack-output` `14-stack-get` `22-stack-graph` `26-destroy` `27-change-secrets-provider`
 
@@ -740,14 +771,22 @@
 
 ```json
 {
-  "deployment": null,
+  "deployment": {
+    "manifest": {
+      "magic": "4ae265b5e36f43e09c7ff61ff9e23e82843496e0c1ad370c8f62bd2c5f0f4369",
+      "time": "1970-01-01T00:00:00Z",
+      "version": "v3.242.0"
+    },
+    "resources": [],
+    "secrets_providers": {}
+  },
   "version": 3
 }
 ```
 
 ---
 
-### 9. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch`
+### 10. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch`
 
 **Calls:** 11 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `12-up` `26-destroy`
 
@@ -1222,7 +1261,7 @@
       "sequence": 14,
       "summaryEvent": {
         "PolicyPacks": {},
-        "durationSeconds": 1,
+        "durationSeconds": 0,
         "isPreview": true,
         "maybeCorrupt": false,
         "resourceChanges": {
@@ -1236,7 +1275,7 @@
 }
 ```
 
-_Replay request body preserved in `body_b64` (1047 gzip bytes)._
+_Replay request body preserved in `body_b64` (1046 gzip bytes)._
 
 **Response** (`200`):
 
@@ -1246,7 +1285,7 @@
 
 ---
```

## Follow-ups when adopting v3.253.0

1. Review the diffs above; adjust stubs/state-db if the protocol moved.
2. Bump `ARG PULUMI_VERSION` in `tools/pulumi/recorder/Dockerfile`, re-run `make pulumi-recorder-discover`, review + commit fixtures/catalog.
3. Bump `github.com/pulumi/pulumi/sdk/v3` in `tools/pulumi/verify/go.mod` to v3.253.0 (lockstep rule).
4. Update the nightly matrix in `.github/workflows/nightly.yml` (keep oldest-supported + new pin).
5. Run the replay/smoke/fault tiers before committing pin bumps.
6. Delete superseded snapshot dirs under `api-snapshots/pulumi/` (see pin audit).
