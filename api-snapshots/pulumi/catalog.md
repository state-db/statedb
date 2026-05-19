# Pulumi HTTP Backend — API Catalog

> Generated 2026-05-19 from `/fixtures/by-command/`
>
> **Recording values:** org=`testorg` · project=`recorder-test` · stack=`dev`
>
> Path parameters are shown as `{org}`, `{project}`, `{stack}`, `{updateID}`.

## Endpoint Index

| # | Method | Path | Calls | Commands |
|---|--------|------|-------|----------|
| 1 | `GET` | `/api/user` | 1 | login |
| 2 | `GET` | `/api/user/organizations/default` | 11 | login, stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 3 | `GET` | `/api/capabilities` | 14 | stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 12 | stack-init, preview, up, stack-ls, stack-export, destroy, stack-rm |
| 5 | `POST` | `/api/stacks/{org}/{project}` | 1 | stack-init |
| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 5 | preview, up, destroy |
| 8 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 7 | preview, up, stack-export, destroy |
| 9 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 8 | preview, up, destroy |
| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 7 | preview, up, destroy |
| 11 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 2 | up |
| 12 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint` | 4 | up, destroy |
| 13 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 8 | up, destroy |
| 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 4 | up |
| 15 | `GET` | `/api/user/stacks` | 1 | stack-ls |
| 16 | `POST` | `/api/stacks/{org}/{project}/{stack}/destroy` | 3 | destroy |
| 17 | `DELETE` | `/api/stacks/{org}/{project}/{stack}` | 1 | stack-rm |

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
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}
```

### `preview`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/capabilities
GET /api/user/organizations/default
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
GET /api/capabilities
GET /api/user/organizations/default
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
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/checkpoint
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
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
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
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

**Calls:** 11 &nbsp;·&nbsp; **Commands:** `01-login` `02-stack-init` `03-preview` `04-up` `05-stack-ls` `06-stack-export` `07-destroy` `08-stack-rm`

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

**Calls:** 14 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-preview` `04-up` `05-stack-ls` `06-stack-export` `07-destroy` `08-stack-rm`

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

### 6. `POST /api/stacks/{org}/{project}/{stack}/preview`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `03-preview`

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
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

**Response** (`200`):

```json
{
  "journalVersion": 1,
  "token": "lease-0001",
  "tokenExpiration": 1779237223,
  "version": 1
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

**Calls:** 8 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `07-destroy`

**Request body:**

```json
{
  "events": [
    {
      "preludeEvent": {
        "config": {}
      },
      "sequence": 0,
      "timestamp": 1779233645
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
      "timestamp": 1779233645
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
      "timestamp": 1779233645
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
          "type": "random:index/randomString:RandomString",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
        },
        "planning": true
      },
      "sequence": 3,
      "timestamp": 1779233645
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
          "type": "random:index/randomPet:RandomPet",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
        },
        "planning": true
      },
      "sequence": 4,
      "timestamp": 1779233645
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
              "id": "04da6b54-80e4-46f7-96ec-b56ff0331ba9",
              "length": 2,
              "separator": "-"
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
          "type": "random:index/randomPet:RandomPet",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
        },
        "planning": true
      },
      "sequence": 5,
      "timestamp": 1779233645
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
              "id": "04da6b54-80e4-46f7-96ec-b56ff0331ba9",
              "length": 8,
              "lower": true,
              "minLower": 0,
              "minNumeric": 0,
              "minSpecial": 0,
              "minUpper": 0,
              "number": true,
              "numeric": true,
              "result": "04da6b54-80e4-46f7-96ec-b56ff0331ba9",
              "special": false,
              "upper": false
            },
            "parent": "urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev",
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::04da6b54-80e4-46f7-96ec-b56ff0331ba9",
          "type": "random:index/randomString:RandomString",
          "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
        },
        "planning": true
      },
      "sequence": 6,
      "timestamp": 1779233645
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
              "petName": "04da6b54-80e4-46f7-96ec-b56ff0331ba9",
              "word": "04da6b54-80e4-46f7-96ec-b56ff0331ba9"
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
      "sequence": 7,
      "timestamp": 1779233645
    },
    {
      "sequence": 8,
      "summaryEvent": {
        "PolicyPacks": {},
        "durationSeconds": 1,
        "isPreview": true,
        "maybeCorrupt": false,
        "resourceChanges": {
          "create": 3
        },
        "result": "succeeded"
      },
      "timestamp": 1779233645
    }
  ]
}
```

**Response** (`200`):

```json
{}
```

---

### 10. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete`

**Calls:** 7 &nbsp;·&nbsp; **Commands:** `03-preview` `04-up` `07-destroy`

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

### 13. `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries`

**Calls:** 8 &nbsp;·&nbsp; **Commands:** `04-up` `07-destroy`

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

**Response** (`200`):

```json
{}
```

---

### 14. `POST /api/stacks/{org}/{project}/{stack}/encrypt`

**Calls:** 4 &nbsp;·&nbsp; **Commands:** `04-up`

**Request body:**

```json
{
  "plaintext": "eyJyZXBsYWNlIjp7InJhdyI6bnVsbH19"
}
```

**Response** (`200`):

```json
{
  "ciphertext": "eyJyZXBsYWNlIjp7InJhdyI6bnVsbH19"
}
```

---

### 15. `GET /api/user/stacks?project=recorder-test`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `05-stack-ls`

**Response** (`200`):

```json
{
  "stacks": []
}
```

---

### 16. `POST /api/stacks/{org}/{project}/{stack}/destroy`

**Calls:** 3 &nbsp;·&nbsp; **Commands:** `07-destroy`

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

### 17. `DELETE /api/stacks/{org}/{project}/{stack}?force=false`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `08-stack-rm`

**Response** (`200`):

```json
{}
```

---

