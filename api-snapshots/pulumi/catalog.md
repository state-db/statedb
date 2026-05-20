# Pulumi HTTP Backend — API Catalog

> Generated from `fixtures/by-command/`
>
> **Recording values:** org=`testorg` · project=`recorder-test` · stack=`dev`
>
> Path parameters are shown as `{org}`, `{project}`, `{stack}`, `{updateID}`.

## Endpoint Index

| # | Method | Path | Calls | Commands |
|---|--------|------|-------|----------|
| 1 | `GET` | `/api/user` | 1 | login |
| 2 | `GET` | `/api/capabilities` | 19 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm |
| 3 | `GET` | `/api/user/organizations/default` | 9 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm |
| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 16 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, stack-rm |
| 5 | `POST` | `/api/stacks/{org}/{project}` | 1 | stack-init |
| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 2 | up |
| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 7 | up, preview, refresh, destroy |
| 8 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 11 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy |
| 9 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 7 | up, preview, refresh, destroy |
| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 7 | up, preview, refresh, destroy |
| 11 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 44 | up, refresh, destroy |
| 12 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 27 | up, refresh, destroy |
| 13 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
| 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/batch-decrypt` | 7 | preview, refresh, destroy |
| 15 | `POST` | `/api/stacks/{org}/{project}/{stack}/refresh` | 2 | refresh |
| 16 | `GET` | `/api/user/stacks` | 1 | stack-ls |
| 17 | `POST` | `/api/stacks/{org}/{project}/{stack}/destroy` | 2 | destroy |
| 18 | `DELETE` | `/api/stacks/{org}/{project}/{stack}` | 1 | stack-rm |

---

## Call Sequences by Command

### `login`

```
GET /api/user
```

### `stack-init`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}
```

### `up`

```
GET /api/capabilities
GET /api/user/organizations/default
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
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `preview`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/preview
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `refresh`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
POST /api/stacks/{org}/{project}/{stack}/refresh
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/refresh
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `stack-ls`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/user/stacks?project=recorder-test
```

### `stack-export`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
```

### `destroy`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
POST /api/stacks/{org}/{project}/{stack}/destroy
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/destroy
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
```

### `stack-export-after-destroy`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
```

### `stack-rm`

```
GET /api/capabilities
GET /api/user/organizations/default
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

### 2. `GET /api/capabilities`

**Calls:** 19 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `10-stack-rm`

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

### 3. `GET /api/user/organizations/default`

**Calls:** 9 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `10-stack-rm`

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

### 4. `GET /api/stacks/{org}/{project}/{stack}`

**Calls:** 16 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `10-stack-rm`

**Response** (`200`):

```json
{
  "activeUpdate": "",
  "currentOperation": null,
  "orgName": "testorg",
  "projectName": "recorder-test",
  "stackName": "dev",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
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
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

_Replay request body preserved in `body_b64` (272 bytes)._

**Response** (`200`):

```json
{
  "activeUpdate": "",
  "currentOperation": null,
  "orgName": "testorg",
  "projectName": "recorder-test",
  "stackName": "dev",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

---

### 6. `POST /api/stacks/{org}/{project}/{stack}/update`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `03-up`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
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
      "pulumi.flag.parallel": "set",
      "pulumi.flag.yes": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.242.0",
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
    "parallel": 1,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

_Replay request body preserved in `body_b64` (914 bytes)._

**Response** (`200`):

```json
{
  "updateID": "update-0001",
  "version": 1
}
```

---

### 7. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "journalVersion": 1,
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

_Replay request body preserved in `body_b64` (273 bytes)._

**Response** (`200`):

```json
{
  "journalVersion": 1,
  "token": "lease-0001",
  "tokenExpiration": 4102444800,
  "version": 1
}
```

---

### 8. `GET /api/stacks/{org}/{project}/{stack}/export`

**Calls:** 11 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `07-stack-export` `08-destroy` `09-stack-export-after-destroy`

**Response** (`200`):

```json
{
  "deployment": null,
  "version": 3
}
```

---

### 9. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "events": [
    {
      "preludeEvent": {
        "config": {}
      },
      "sequence": 0,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "id": "",
            "inputs": {},
            "outputs": {},
            "parent": "",
            "provider": "",
            "type": "pulumi:pulumi:Stack",
            "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
          },
          "old": null,
          "op": "create",
          "provider": "",
          "type": "pulumi:pulumi:Stack",
          "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
        },
        "planning": true
      },
      "sequence": 1,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "id": "",
            "inputs": {},
            "outputs": {},
            "parent": "",
            "provider": "",
            "type": "pulumi:pulumi:Stack",
            "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
          },
          "old": null,
          "op": "create",
          "provider": "",
          "type": "pulumi:pulumi:Stack",
          "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
        },
        "planning": true
      },
      "sequence": 2,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 2,
              "separator": "-"
            },
            "outputs": {},
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomPet:RandomPet",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
        },
        "planning": true
      },
      "sequence": 3,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 2,
              "separator": "-"
            },
            "outputs": {
              "id": "[resource-id]",
              "length": 2,
              "separator": "-"
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomPet:RandomPet",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
        },
        "planning": true
      },
      "sequence": 4,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 8,
              "special": false,
              "upper": false
            },
            "outputs": {},
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomString:RandomString",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
        },
        "planning": true
      },
      "sequence": 5,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 8,
              "special": false,
              "upper": false
            },
            "outputs": {
              "id": "[resource-id]",
              "length": 8,
              "lower": true,
              "minLower": 0,
              "minNumeric": 0,
              "minSpecial": 0,
              "minUpper": 0,
              "number": true,
              "numeric": true,
              "result": "[result]",
              "special": false,
              "upper": false
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomString:RandomString",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
        },
        "planning": true
      },
      "sequence": 6,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "max": 42,
              "min": 42,
              "seed": "recorder-pin"
            },
            "outputs": {},
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomInteger:RandomInteger",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomInteger:RandomInteger",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
        },
        "planning": true
      },
      "sequence": 7,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "max": 42,
              "min": 42,
              "seed": "recorder-pin"
            },
            "outputs": {
              "id": "[resource-id]",
              "max": 42,
              "min": 42,
              "result": "[result]",
              "seed": "recorder-pin"
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomInteger:RandomInteger",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomInteger:RandomInteger",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
        },
        "planning": true
      },
      "sequence": 8,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 14,
              "lower": true,
              "minLower": 3,
              "minNumeric": 2,
              "minSpecial": 1,
              "minUpper": 2,
              "numeric": true,
              "overrideSpecial": "_-",
              "special": true,
              "upper": true
            },
            "outputs": {},
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomPassword:RandomPassword",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomPassword:RandomPassword",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
        },
        "planning": true
      },
      "sequence": 9,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "length": 14,
              "lower": true,
              "minLower": 3,
              "minNumeric": 2,
              "minSpecial": 1,
              "minUpper": 2,
              "numeric": true,
              "overrideSpecial": "_-",
              "special": true,
              "upper": true
            },
            "outputs": {
              "bcryptHash": {
                "4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
                "ciphertext": "[secret]"
              },
              "id": "[resource-id]",
              "length": 14,
              "lower": true,
              "minLower": 3,
              "minNumeric": 2,
              "minSpecial": 1,
              "minUpper": 2,
              "number": true,
              "numeric": true,
              "overrideSpecial": "_-",
              "result": {
                "4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
                "ciphertext": "[secret]"
              },
              "special": true,
              "upper": true
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomPassword:RandomPassword",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomPassword:RandomPassword",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
        },
        "planning": true
      },
      "sequence": 10,
      "timestamp": 0
    },
    {
      "resourcePreEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "byteLength": 6,
              "keepers": {
                "project": "recorder-test",
                "purpose": "api-shape"
              },
              "prefix": "rec-"
            },
            "outputs": {},
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomId:RandomId",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomId:RandomId",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
        },
        "planning": true
      },
      "sequence": 11,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "custom": true,
            "id": "",
            "inputs": {
              "byteLength": 6,
              "keepers": {
                "project": "recorder-test",
                "purpose": "api-shape"
              },
              "prefix": "rec-"
            },
            "outputs": {
              "b64Std": "[b64-std]",
              "b64Url": "[b64-url]",
              "byteLength": 6,
              "dec": "[decimal-id]",
              "hex": "[hex]",
              "id": "[resource-id]",
              "keepers": {
                "project": "recorder-test",
                "purpose": "api-shape"
              },
              "prefix": "rec-"
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
            "type": "random:index/randomId:RandomId",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::00000000-0000-0000-0000-000000000000",
          "type": "random:index/randomId:RandomId",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
        },
        "planning": true
      },
      "sequence": 12,
      "timestamp": 0
    },
    {
      "resOutputsEvent": {
        "metadata": {
          "detailedDiff": null,
          "logical": true,
          "new": {
            "id": "",
            "inputs": {},
            "outputs": {
              "password": "[secret]",
              "petName": "[pet-name]",
              "pin": "00000000-0000-0000-0000-000000000000",
              "tokenHex": "[hex]",
              "word": "[word]"
            },
            "parent": "",
            "provider": "",
            "type": "pulumi:pulumi:Stack",
            "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
          },
          "old": null,
          "op": "create",
          "provider": "",
          "type": "pulumi:pulumi:Stack",
          "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
        },
        "planning": true
      },
      "sequence": 13,
      "timestamp": 0
    },
    {
      "sequence": 14,
      "summaryEvent": {
        "PolicyPacks": {},
        "durationSeconds": 1,
        "isPreview": true,
        "maybeCorrupt": false,
        "resourceChanges": {
          "create": 6
        },
        "result": "succeeded"
      },
      "timestamp": 0
    }
  ]
}
```

_Replay request body preserved in `body_b64` (1047 gzip bytes)._

**Response** (`200`):

```json
{}
```

---

### 10. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "status": "succeeded"
}
```

_Replay request body preserved in `body_b64` (22 bytes)._

**Response** (`200`):

```json
{}
```

---

### 11. `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries`

**Calls:** 44 &nbsp;·&nbsp; **Commands:** `03-up` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "entries": [
    {
      "kind": 6,
      "operationID": 0,
      "removeNew": null,
      "removeOld": null,
      "secretsProvider": {
        "state": {
          "owner": "testorg",
          "project": "recorder-test",
          "stack": "dev",
          "url": "http://localhost:8080"
        },
        "type": "service"
      },
      "sequenceID": 1,
      "version": 1
    },
    {
      "kind": 0,
      "operation": {
        "resource": {
          "custom": false,
          "sourcePosition": "project:///../home/runner/work/pulumi-yaml/pulumi-yaml/pkg/server/server.go#326",
          "stackTrace": [
            {
              "sourcePosition": "project:///../home/runner/work/pulumi-yaml/pulumi-yaml/pkg/server/server.go#326"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/github.com/pulumi/pulumi/sdk/v3@v3.237.0/proto/go/language_grpc.pb.go#588"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/github.com/pulumi/pulumi/sdk/v3@v3.237.0/go/common/util/rpcutil/interceptor.go#301"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1243"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/github.com/grpc-ecosystem/grpc-opentracing@v0.0.0-20180507213350-8e809c8a8645/go/otgrpc/server.go#57"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1243"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/github.com/pulumi/pulumi/sdk/v3@v3.237.0/go/common/util/rpcutil/interceptor.go#228"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1234"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/github.com/pulumi/pulumi/sdk/v3@v3.237.0/proto/go/language_grpc.pb.go#590"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1430"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1856"
            },
            {
              "sourcePosition": "project:///../home/runner/go/pkg/mod/google.golang.org/grpc@v1.81.0/server.go#1065"
            },
            {
              "sourcePosition": "project:///../opt/hostedtoolcache/go/1.25.9/x64/src/runtime/asm_arm64.s#1268"
            }
          ],
          "type": "pulumi:pulumi:Stack",
          "urn": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev"
        },
        "type": "creating"
      },
      "operationID": 1,
      "removeNew": null,
      "removeOld": null,
      "sequenceID": 2,
      "version": 1
    }
  ]
}
```

_Replay request body preserved in `body_b64` (591 gzip bytes)._

**Response** (`200`):

```json
{}
```

---

### 12. `POST /api/stacks/{org}/{project}/{stack}/encrypt`

**Calls:** 27 &nbsp;·&nbsp; **Commands:** `03-up` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "plaintext": "[secret]"
}
```

_Replay request body preserved in `body_b64` (24 bytes)._

**Response** (`200`):

```json
{
  "ciphertext": "[secret]"
}
```

---

### 13. `POST /api/stacks/{org}/{project}/{stack}/preview`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `04-preview`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
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
      "pulumi.flag.parallel": "set",
      "pulumi.os": "linux",
      "pulumi.version": "v3.242.0",
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
    "parallel": 1,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

_Replay request body preserved in `body_b64` (889 bytes)._

**Response** (`200`):

```json
{
  "updateID": "preview-0003",
  "version": 1
}
```

---

### 14. `POST /api/stacks/{org}/{project}/{stack}/batch-decrypt`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `04-preview` `05-refresh` `08-destroy`

**Request body:**

```json
{
  "ciphertexts": [
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]",
    "[secret]"
  ]
}
```

_Replay request body preserved in `body_b64` (55 gzip bytes)._

**Response** (`200`):

```json
{
  "plaintexts": {
    "[secret]": "[secret]"
  }
}
```

---

### 15. `POST /api/stacks/{org}/{project}/{stack}/refresh`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `05-refresh`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
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
      "pulumi.flag.parallel": "set",
      "pulumi.flag.yes": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.242.0",
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
    "parallel": 1,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

_Replay request body preserved in `body_b64` (892 bytes)._

**Response** (`200`):

```json
{
  "updateID": "refresh-0004",
  "version": 1
}
```

---

### 16. `GET /api/user/stacks?project=recorder-test`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `06-stack-ls`

**Response** (`200`):

```json
{
  "stacks": []
}
```

---

### 17. `POST /api/stacks/{org}/{project}/{stack}/destroy`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `08-destroy`

**Request body:**

```json
{
  "config": {},
  "description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
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
      "pulumi.flag.parallel": "set",
      "pulumi.flag.yes": "true",
      "pulumi.os": "linux",
      "pulumi.version": "v3.242.0",
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
    "parallel": 1,
    "showConfig": false,
    "showNames": false,
    "showReplacementSteps": false,
    "summary": false
  },
  "runtime": "yaml"
}
```

_Replay request body preserved in `body_b64` (892 bytes)._

**Response** (`200`):

```json
{
  "updateID": "destroy-0006",
  "version": 1
}
```

---

### 18. `DELETE /api/stacks/{org}/{project}/{stack}?force=false`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `10-stack-rm`

**Response** (`200`):

```json
{}
```

---

