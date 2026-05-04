# Pulumi HTTP Backend — API Catalog

> Generated 2026-05-04 from `/fixtures/by-command/`
>
> **Recording values:** org=`testorg` · project=`recorder-test` · stack=`dev`
>
> Path parameters are shown as `{org}`, `{project}`, `{stack}`, `{updateID}`.

## Endpoint Index

| # | Method | Path | Calls | Commands |
|---|--------|------|-------|----------|
| 1 | `GET` | `/api/user` | 1 | login |
| 2 | `GET` | `/api/user/organizations/default` | 8 | login, stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 3 | `GET` | `/api/capabilities` | 10 | stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 12 | stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 5 | `POST` | `/api/stacks/{org}/{project}` | 1 | stack-init |
| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 5 | preview, up, destroy |
| 8 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 7 | preview, up, stack-export, destroy |
| 9 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 5 | preview, up, destroy |
| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 5 | preview, up, destroy |
| 11 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 2 | up |
| 12 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint` | 4 | up, destroy |
| 13 | `GET` | `/api/user/stacks` | 1 | stack-ls |
| 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/destroy` | 2 | destroy |
| 15 | `DELETE` | `/api/stacks/{org}/{project}/{stack}` | 1 | stack-rm |

---

## Call Sequences by Command

### `login`

```
GET /api/user
GET /api/user/organizations/default
```

### `stack-init`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}
```

### `preview`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/preview
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `up`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/update
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/update
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `stack-ls`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
GET /api/user/stacks?project=recorder-test
```

### `stack-export`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
```

### `destroy`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/destroy
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/destroy
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `stack-rm`

```
GET /api/user/organizations/default
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
DELETE /api/stacks/{org}/{project}/{stack}?force=false
```

---

## Endpoint Details

### 1. `GET /api/user`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `01-login`

**Response** (`200`):

```json
{
  "email": "test@example.com",
  "githubLogin": "testorg",
  "name": "Test User",
  "organizations": [
    {
      "githubLogin": "testorg",
      "name": "testorg"
    }
  ]
}
```

---

### 2. `GET /api/user/organizations/default`

**Calls:** 8 &nbsp;·&nbsp; **Commands:** `01-login` `02-stack-init` `03-preview` `04-up` `05-stack-ls` `06-stack-export` `07-destroy` `08-stack-rm`

**Response** (`200`):

```json
{
  "organization": {
    "githubLogin": "testorg",
    "name": "testorg"
  }
}
```

---

### 3. `GET /api/capabilities`

**Calls:** 10 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-preview` `04-up` `05-stack-ls` `06-stack-export` `07-destroy` `08-stack-rm`

**Response** (`200`):

```json
{
  "capabilities": [
    {
      "name": "delta-checkpoint-updates",
      "version": 1
    }
  ]
}
```

---

### 4. `GET /api/stacks/{org}/{project}/{stack}`

**Calls:** 12 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-preview` `04-up` `05-stack-ls` `06-stack-export` `07-destroy` `08-stack-rm`

**Response** (`200`):

```json
{
  "activeUpdate": "",
  "currentOperation": null,
  "orgName": "testorg",
  "projectName": "recorder-test",
  "stackName": "dev",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording — no resources, no providers.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

---

### 5. `POST /api/stacks/{org}/{project}`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `02-stack-init`

**Request body:**

```json
{
  "stackName": "dev",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording — no resources, no providers.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

**Response** (`200`):

```json
{
  "activeUpdate": "",
  "currentOperation": null,
  "orgName": "testorg",
  "projectName": "recorder-test",
  "stackName": "dev",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording — no resources, no providers.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

---

### 6. `POST /api/stacks/{org}/{project}/{stack}/preview`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `03-preview`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording — no resources, no providers.",
  "main": "",
  "metadata": {
    "environment": {
      "exec.kind": "cli",
      "pulumi.arch": "arm64",
      "pulumi.env.PULUMI_ACCESS_TOKEN": "set",
      "pulumi.env.PULUMI_BACKEND_URL": "set",
      "pulumi.env.PULUMI_CONFIG_PASSPHRASE": "set",
      "pulumi.env.PULUMI_HOME": "set",
      "pulumi.env.PULUMI_SKIP_CONFIRMATIONS": "true",
      "pulumi.env.PULUMI_SKIP_UPDATE_CHECK": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.234.0",
      "runtime.name": "yaml",
      "stack.environments": "[]",
      "updatePlan": "false"
    },
    "message": ""
  },
  "name": "recorder-test",
  "options": {
    "color": "raw",
    "debug": false,
    "dryRun": true,
    "localPolicyPackPaths": [],
    "parallel": 40,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

**Response** (`200`):

```json
{
  "updateID": "preview-0001",
  "version": 1
}
```

---

### 7. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`

**Calls:** 5 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `07-destroy`

**Request body:**

```json
{
  "journalVersion": 1,
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording — no resources, no providers.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

**Response** (`200`):

```json
{
  "duration": 3600,
  "token": "lease-0001"
}
```

---

### 8. `GET /api/stacks/{org}/{project}/{stack}/export`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `06-stack-export` `07-destroy`

**Response** (`200`):

```json
{
  "deployment": null,
  "version": 3
}
```

---

### 9. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch`

**Calls:** 5 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `07-destroy`

**Request body:**

```json
// binary payload (652 bytes) — likely gzip-compressed
```

**Response** (`200`):

```json
{}
```

---

### 10. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete`

**Calls:** 5 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `07-destroy`

**Request body:**

```json
{
  "status": "succeeded"
}
```

**Response** (`200`):

```json
{}
```

---

### 11. `POST /api/stacks/{org}/{project}/{stack}/update`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `04-up`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording — no resources, no providers.",
  "main": "",
  "metadata": {
    "environment": {
      "exec.kind": "cli",
      "pulumi.arch": "arm64",
      "pulumi.env.PULUMI_ACCESS_TOKEN": "set",
      "pulumi.env.PULUMI_BACKEND_URL": "set",
      "pulumi.env.PULUMI_CONFIG_PASSPHRASE": "set",
      "pulumi.env.PULUMI_HOME": "set",
      "pulumi.env.PULUMI_SKIP_CONFIRMATIONS": "true",
      "pulumi.env.PULUMI_SKIP_UPDATE_CHECK": "true",
      "pulumi.flag.yes": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.234.0",
      "runtime.name": "yaml",
      "stack.environments": "[]",
      "updatePlan": "false"
    },
    "message": ""
  },
  "name": "recorder-test",
  "options": {
    "color": "raw",
    "debug": false,
    "dryRun": true,
    "localPolicyPackPaths": [],
    "parallel": 40,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

**Response** (`200`):

```json
{
  "updateID": "update-0002",
  "version": 1
}
```

---

### 12. `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint`

**Calls:** 4 &nbsp;·&nbsp; **Commands:** `04-up` `07-destroy`

**Request body:**

```json
// binary payload (1233 bytes) — likely gzip-compressed
```

**Response** (`200`):

```json
{}
```

---

### 13. `GET /api/user/stacks?project=recorder-test`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `05-stack-ls`

**Response** (`200`):

```json
{
  "stacks": []
}
```

---

### 14. `POST /api/stacks/{org}/{project}/{stack}/destroy`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `07-destroy`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording — no resources, no providers.",
  "main": "",
  "metadata": {
    "environment": {
      "exec.kind": "cli",
      "pulumi.arch": "arm64",
      "pulumi.env.PULUMI_ACCESS_TOKEN": "set",
      "pulumi.env.PULUMI_BACKEND_URL": "set",
      "pulumi.env.PULUMI_CONFIG_PASSPHRASE": "set",
      "pulumi.env.PULUMI_HOME": "set",
      "pulumi.env.PULUMI_SKIP_CONFIRMATIONS": "true",
      "pulumi.env.PULUMI_SKIP_UPDATE_CHECK": "true",
      "pulumi.flag.yes": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.234.0",
      "stack.environments": "[]",
      "updatePlan": "false"
    },
    "message": ""
  },
  "name": "recorder-test",
  "options": {
    "color": "raw",
    "debug": false,
    "dryRun": true,
    "localPolicyPackPaths": [],
    "parallel": 40,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

**Response** (`200`):

```json
{
  "updateID": "destroy-0004",
  "version": 1
}
```

---

### 15. `DELETE /api/stacks/{org}/{project}/{stack}?force=false`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `08-stack-rm`

**Response** (`200`):

```json
{}
```

---

