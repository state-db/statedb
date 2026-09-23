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
| 2 | `GET` | `/api/capabilities` | 56 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
| 3 | `GET` | `/api/user/organizations/default` | 32 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-ls, stack-rm, stack-init-for-cancel, cancel, stack-rm-after-cancel |
| 4 | `GET` | `/api/stacks/{org}/{project}/{stack}` | 47 | stack-init, up, preview, refresh, stack-ls, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, stack-rm, stack-init, up, stack-output, stack-get, stack-history, stack-tag-set, stack-tag-ls, stack-tag-get, stack-tag-rm, stack-select, stack-graph, stack-schedule, stack-webhook, destroy, change-secrets-provider, stack-rename, stack-init-for-cancel, cancel, stack-rm-after-cancel |
| 5 | `POST` | `/api/stacks/{org}/{project}` | 3 | stack-init, stack-init, stack-init-for-cancel |
| 6 | `POST` | `/api/stacks/{org}/{project}/{stack}/encrypt` | 71 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
| 7 | `POST` | `/api/stacks/{org}/{project}/{stack}/update` | 6 | up, up-after-destroy, up |
| 8 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
| 9 | `GET` | `/api/stacks/{org}/{project}/{stack}/export` | 25 | up, preview, refresh, stack-export, destroy, stack-export-after-destroy, up-after-destroy, destroy-after-rebuild, up, stack-output, stack-get, stack-graph, destroy, change-secrets-provider |
| 10 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
| 11 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/complete` | 15 | up, preview, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
| 12 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries` | 104 | up, refresh, destroy, up-after-destroy, destroy-after-rebuild, up, destroy |
| 13 | `POST` | `/api/stacks/{org}/{project}/{stack}/preview` | 1 | preview |
| 14 | `POST` | `/api/stacks/{org}/{project}/{stack}/batch-decrypt` | 16 | preview, refresh, destroy, destroy-after-rebuild, stack-output, stack-get, stack-graph, destroy |
| 15 | `POST` | `/api/stacks/{org}/{project}/{stack}/refresh` | 2 | refresh |
| 16 | `GET` | `/api/user/stacks` | 2 | stack-ls, stack-ls |
| 17 | `POST` | `/api/stacks/{org}/{project}/{stack}/destroy` | 6 | destroy, destroy-after-rebuild, destroy |
| 18 | `DELETE` | `/api/stacks/{org}/{project}/{stack}` | 2 | stack-rm, stack-rm-after-cancel |
| 19 | `POST` | `/api/stacks/{org}/{project}/{stack}/decrypt/log-batch-decryption` | 1 | stack-output |
| 20 | `GET` | `/api/stacks/{org}/{project}/{stack}/updates` | 1 | stack-history |
| 21 | `PATCH` | `/api/stacks/{org}/{project}/{stack}/tags` | 2 | stack-tag-set, stack-tag-rm |
| 22 | `GET` | `/api/stacks/{org}/{project}/{stack}/deployments/schedules` | 1 | stack-schedule |
| 23 | `GET` | `/api/stacks/{org}/{project}/{stack}/hooks` | 1 | stack-webhook |
| 24 | `POST` | `/api/stacks/{org}/{project}/{stack}/import` | 1 | change-secrets-provider |
| 25 | `GET` | `/api/stacks/{org}/{project}/{stack}/update` | 1 | change-secrets-provider |
| 26 | `POST` | `/api/stacks/{org}/{project}/{stack}/rename` | 1 | stack-rename |
| 27 | `GET` | `/api/stacks/{org}/{project}/staging` | 2 | stack-ls, stack-rm |
| 28 | `DELETE` | `/api/stacks/{org}/{project}/staging` | 1 | stack-rm |
| 29 | `POST` | `/api/stacks/{org}/{project}/{stack}/update/{updateID}/cancel` | 1 | cancel |

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
POST /api/stacks/{org}/{project}/{stack}/encrypt
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
POST /api/stacks/{org}/{project}/{stack}/encrypt
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
POST /api/stacks/{org}/{project}/{stack}/encrypt
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
POST /api/stacks/{org}/{project}/{stack}/encrypt
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
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries
POST /api/stacks/{org}/{project}/{stack}/encrypt
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

### `up-after-destroy`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/encrypt
POST /api/stacks/{org}/{project}/{stack}/update
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete
POST /api/stacks/{org}/{project}/{stack}/update
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
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

### `destroy-after-rebuild`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
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

### `stack-rm`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
DELETE /api/stacks/{org}/{project}/{stack}?force=false
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
POST /api/stacks/{org}/{project}/{stack}/encrypt
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

### `stack-output`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
POST /api/stacks/{org}/{project}/{stack}/decrypt/log-batch-decryption
```

### `stack-get`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
GET /api/stacks/{org}/{project}/{stack}
```

### `stack-history`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/updates?pageSize=10&page=1
```

### `stack-tag-set`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/tags
```

### `stack-tag-ls`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
```

### `stack-tag-get`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
```

### `stack-tag-rm`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
PATCH /api/stacks/{org}/{project}/{stack}/tags
```

### `stack-select`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
```

### `stack-graph`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
```

### `stack-drift`

```
```

### `stack-schedule`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/deployments/schedules
```

### `stack-webhook`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/hooks
```

### `destroy`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/batch-decrypt
POST /api/stacks/{org}/{project}/{stack}/encrypt
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

### `change-secrets-provider`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}/export
GET /api/capabilities
POST /api/stacks/{org}/{project}/{stack}/import
GET /api/stacks/{org}/{project}/{stack}/update
```

### `stack-rename`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/rename
```

### `stack-ls`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/staging
GET /api/user/stacks?project=recorder-test
```

### `stack-rm`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/staging
DELETE /api/stacks/{org}/{project}/staging?force=false
```

### `stack-init-for-cancel`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}
```

### `cancel`

```
GET /api/capabilities
GET /api/user/organizations/default
GET /api/stacks/{org}/{project}/{stack}
GET /api/stacks/{org}/{project}/{stack}
POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/cancel
```

### `stack-rm-after-cancel`

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
  "githubLogin": "Test User",
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

**Calls:** 56 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`

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

**Calls:** 32 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `29-stack-ls` `30-stack-rm` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`

**Response** (`200`):

```json
{
  "GitHubLogin": "testorg"
}
```

---

### 4. `GET /api/stacks/{org}/{project}/{stack}`

**Calls:** 47 &nbsp;·&nbsp; **Commands:** `02-stack-init` `03-up` `04-preview` `05-refresh` `06-stack-ls` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `10-stack-rm` `11-stack-init` `12-up` `13-stack-output` `14-stack-get` `15-stack-history` `16-stack-tag-set` `17-stack-tag-ls` `18-stack-tag-get` `19-stack-tag-rm` `20-stack-select` `22-stack-graph` `24-stack-schedule` `25-stack-webhook` `26-destroy` `27-change-secrets-provider` `28-stack-rename` `31-stack-init-for-cancel` `32-cancel` `33-stack-rm-after-cancel`

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

**Calls:** 3 &nbsp;·&nbsp; **Commands:** `02-stack-init` `11-stack-init` `31-stack-init-for-cancel`

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

### 6. `POST /api/stacks/{org}/{project}/{stack}/encrypt`

**Calls:** 71 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`

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

### 7. `POST /api/stacks/{org}/{project}/{stack}/update`

**Calls:** 6 &nbsp;·&nbsp; **Commands:** `03-up` `09a-up-after-destroy` `12-up`

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
      "pulumi.version": "[cli-version]",
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

_Replay request body preserved in `body_b64` (919 bytes)._

**Response** (`200`):

```json
{
  "updateID": "update-0001",
  "version": 1
}
```

---

### 8. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}`

**Calls:** 15 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`

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

### 9. `GET /api/stacks/{org}/{project}/{stack}/export`

**Calls:** 25 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `07-stack-export` `08-destroy` `09-stack-export-after-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `13-stack-output` `14-stack-get` `22-stack-graph` `26-destroy` `27-change-secrets-provider`

**Response** (`200`):

```json
{
  "deployment": {
    "manifest": {
      "magic": "4ae265b5e36f43e09c7ff61ff9e23e82843496e0c1ad370c8f62bd2c5f0f4369",
      "time": "1970-01-01T00:00:00Z",
      "version": "v3.242.0"
    },
    "resources": []
  },
  "version": 3
}
```

---

### 10. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/events/batch`

**Calls:** 15 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`

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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomPet:RandomPet",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPet:RandomPet::pet"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomString:RandomString",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomString:RandomString::word"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomInteger:RandomInteger",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomInteger:RandomInteger",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomInteger:RandomInteger::pin"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomPassword:RandomPassword",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomPassword:RandomPassword",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomPassword:RandomPassword::password"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomId:RandomId",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
            "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
            "type": "random:index/randomId:RandomId",
            "urn": "urn:pulumi:dev::recorder-test::random:index/randomId:RandomId::token"
          },
          "old": null,
          "op": "create",
          "provider": "urn:pulumi:dev::recorder-test::pulumi:providers:random::default::[resource-id]",
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
        "durationSeconds": 0,
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

_Replay request body preserved in `body_b64` (1046 gzip bytes)._

**Response** (`200`):

```json
{}
```

---

### 11. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/complete`

**Calls:** 15 &nbsp;·&nbsp; **Commands:** `03-up` `04-preview` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`

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

### 12. `PATCH /api/stacks/{org}/{project}/{stack}/update/{updateID}/journalentries`

**Calls:** 104 &nbsp;·&nbsp; **Commands:** `03-up` `05-refresh` `08-destroy` `09a-up-after-destroy` `09b-destroy-after-rebuild` `12-up` `26-destroy`

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
          "sourcePosition": "[source-position]",
          "stackTrace": [
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
            },
            {
              "sourcePosition": "[source-position]"
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

_Replay request body preserved in `body_b64` (313 gzip bytes)._

**Response** (`200`):

```json
{}
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
      "pulumi.version": "[cli-version]",
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

_Replay request body preserved in `body_b64` (894 bytes)._

**Response** (`200`):

```json
{
  "updateID": "preview-0003",
  "version": 1
}
```

---

### 14. `POST /api/stacks/{org}/{project}/{stack}/batch-decrypt`

**Calls:** 16 &nbsp;·&nbsp; **Commands:** `04-preview` `05-refresh` `08-destroy` `09b-destroy-after-rebuild` `13-stack-output` `14-stack-get` `22-stack-graph` `26-destroy`

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
      "pulumi.version": "[cli-version]",
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

_Replay request body preserved in `body_b64` (897 bytes)._

**Response** (`200`):

```json
{
  "updateID": "refresh-0004",
  "version": 1
}
```

---

### 16. `GET /api/user/stacks?project=recorder-test`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `06-stack-ls` `29-stack-ls`

**Response** (`200`):

```json
{
  "stacks": []
}
```

---

### 17. `POST /api/stacks/{org}/{project}/{stack}/destroy`

**Calls:** 6 &nbsp;·&nbsp; **Commands:** `08-destroy` `09b-destroy-after-rebuild` `26-destroy`

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
      "pulumi.version": "[cli-version]",
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

_Replay request body preserved in `body_b64` (897 bytes)._

**Response** (`200`):

```json
{
  "updateID": "destroy-0006",
  "version": 1
}
```

---

### 18. `DELETE /api/stacks/{org}/{project}/{stack}?force=false`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `10-stack-rm` `33-stack-rm-after-cancel`

**Response** (`200`):

```json
{}
```

---

### 19. `POST /api/stacks/{org}/{project}/{stack}/decrypt/log-batch-decryption`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `13-stack-output`

**Request body:**

```json
{
  "commandName": "pulumi stack output"
}
```

_Replay request body preserved in `body_b64` (37 bytes)._

**Response** (`200`):

```json
{
  "token": "lease-fallback-log-batch-decryption",
  "tokenExpiration": 4102444800,
  "version": 1
}
```

---

### 20. `GET /api/stacks/{org}/{project}/{stack}/updates?pageSize=10&page=1`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `15-stack-history`

**Response** (`200`):

```json
{
  "itemsPerPage": 10,
  "updates": []
}
```

---

### 21. `PATCH /api/stacks/{org}/{project}/{stack}/tags`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `16-stack-tag-set` `19-stack-tag-rm`

**Request body:**

```json
{
  "env": "recorder",
  "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
  "pulumi:project": "recorder-test",
  "pulumi:runtime": "yaml"
}
```

_Replay request body preserved in `body_b64` (262 bytes)._

**Response** (`200`):

```json
{}
```

---

### 22. `GET /api/stacks/{org}/{project}/{stack}/deployments/schedules`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `24-stack-schedule`

**Response** (`200`):

```json
{
  "status": "running",
  "updateID": "schedules"
}
```

---

### 23. `GET /api/stacks/{org}/{project}/{stack}/hooks`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `25-stack-webhook`

**Response** (`404`):

```json
{
  "detail": "GET api/stacks/testorg/recorder-test/dev/hooks",
  "message": "unhandled stacks path"
}
```

---

### 24. `POST /api/stacks/{org}/{project}/{stack}/import`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `27-change-secrets-provider`

**Request body:**

```json
{
  "deployment": {
    "manifest": {
      "magic": "4ae265b5e36f43e09c7ff61ff9e23e82843496e0c1ad370c8f62bd2c5f0f4369",
      "time": "1970-01-01T00:00:00Z",
      "version": "v3.242.0"
    },
    "metadata": {},
    "secrets_providers": {
      "state": {
        "salt": "[salt]"
      },
      "type": "passphrase"
    }
  },
  "version": 3
}
```

_Replay request body preserved in `body_b64` (208 gzip bytes)._

**Response** (`200`):

```json
{}
```

---

### 25. `GET /api/stacks/{org}/{project}/{stack}/update`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `27-change-secrets-provider`

**Response** (`404`):

```json
{
  "detail": "GET api/stacks/testorg/recorder-test/dev/update",
  "message": "unhandled stacks path"
}
```

---

### 26. `POST /api/stacks/{org}/{project}/{stack}/rename`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `28-stack-rename`

**Request body:**

```json
{
  "newName": "staging",
  "newProject": "recorder-test"
}
```

_Replay request body preserved in `body_b64` (50 bytes)._

**Response** (`200`):

```json
{}
```

---

### 27. `GET /api/stacks/{org}/{project}/staging`

**Calls:** 2 &nbsp;·&nbsp; **Commands:** `29-stack-ls` `30-stack-rm`

**Response** (`200`):

```json
{
  "activeUpdate": "",
  "currentOperation": null,
  "orgName": "testorg",
  "projectName": "recorder-test",
  "stackName": "staging",
  "tags": {
    "pulumi:description": "Minimal Pulumi project for API recording. Uses the `random` provider so `pulumi up`/`destroy` exercise the journal/checkpoint write paths with real resource state.",
    "pulumi:project": "recorder-test",
    "pulumi:runtime": "yaml"
  }
}
```

---

### 28. `DELETE /api/stacks/{org}/{project}/staging?force=false`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `30-stack-rm`

**Response** (`200`):

```json
{}
```

---

### 29. `POST /api/stacks/{org}/{project}/{stack}/update/{updateID}/cancel`

**Calls:** 1 &nbsp;·&nbsp; **Commands:** `32-cancel`

**Response** (`204`):

```json
null
```

---
