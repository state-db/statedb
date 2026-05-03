# API Comparison: v3.233.0 → v3.234.0

| | Count |
|---|---|
| Endpoints in v3.233.0 | 82 |
| Endpoints in v3.234.0 | 82 |
| Added | 0 |
| Removed | 0 |

No endpoint changes between these versions.
## Endpoints by category in v3.234.0

### account (1)

- `GET /api/orgs/{orgName}/templates/download`

### checkpoint (3)

- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpointverbatim`

### deployments (3)

- `DELETE /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings/encrypt`
- `PUT /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings`

### miscellaneous (1)

- `GET /api/capabilities`

### organizations (2)

- `GET /api/orgs/{orgName}/templates`
- `HEAD /api/stacks/{orgName}/{projectName}`

### other (1)

- `GET /api/console/orgs/{orgName}/integrations/github-app`

### policy_packs (1)

- `POST /api/orgs/{orgName}/policypacks`

### registry (5)

- `DELETE /api/registry/packages/{source}/{publisher}/{name}/versions/{version}`
- `POST /api/registry/packages/{source}/{publisher}/{name}/versions`
- `POST /api/registry/packages/{source}/{publisher}/{name}/versions/{version}/complete`
- `POST /api/registry/templates/{source}/{publisher}/{name}/versions`
- `POST /api/registry/templates/{source}/{publisher}/{name}/versions/{version}/complete`

### resource_search (2)

- `GET /api/orgs/{orgName}/search/resources`
- `GET /api/orgs/{orgName}/search/resources/parse`

### stack_config (1)

- `PUT /api/stacks/{orgName}/{projectName}/{stackName}/config`

### stack_tags (1)

- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/tags`

### stack_updates (2)

- `GET /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates`

### stacks (53)

- `DELETE /api/stacks/{orgName}/{projectName}/{stackName}`
- `GET /api/stacks/{orgName}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/export`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/logs`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/latest`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/journalentries`
- `POST /api/stacks/{orgName}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/batch-decrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/batch-encrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/decrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/encrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/import`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/rename`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/renew_lease`

### update_create (2)

- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}/contents/file/{path:.*}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}/contents/files`

### update_lifecycle (1)

- `POST /api/orgs/{orgName}/policypacks/{policyPackName}/{versionTag}/complete`

### users (3)

- `GET /api/user`
- `GET /api/user/organizations/default`
- `GET /api/user/stacks`

