# API Comparison: v3.242.0 → v3.259.0

| | Count |
|---|---|
| Endpoints in v3.242.0 | 89 |
| Endpoints in v3.259.0 | 89 |
| Added | 0 |
| Removed | 0 |

No endpoint changes between these versions.
## Endpoints by category in v3.259.0

### account (7)

- `GET /api/orgs/{orgName}/members`
- `GET /api/orgs/{orgName}/resources/summary`
- `GET /api/orgs/{orgName}/templates`
- `GET /api/orgs/{orgName}/templates/download`
- `GET /api/user`
- `GET /api/user/organizations/default`
- `GET /api/user/stacks`

### checkpoint (12)

- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/checkpointverbatim`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpoint`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpointdelta`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/checkpointverbatim`

### deployments (3)

- `DELETE /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/deployments`
- `PUT /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings`

### events (8)

- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/events/batch`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/events`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/events/batch`

### locking (4)

- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/renew_lease`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/renew_lease`

### other (2)

- `GET /api/capabilities`
- `GET /api/console/orgs/{orgName}/integrations/github-app`

### policy (1)

- `POST /api/orgs/{orgName}/policypacks`

### registry (3)

- `DELETE /api/registry/packages/{source}/{publisher}/{name}/versions/{version}`
- `POST /api/registry/packages/{source}/{publisher}/{name}/versions`
- `POST /api/registry/templates/{source}/{publisher}/{name}/versions`

### search (3)

- `GET /api/orgs/{orgName}/search/resources`
- `GET /api/orgs/{orgName}/search/resources/parse`
- `GET /api/orgs/{orgName}/search/resourcesv2`

### secrets (3)

- `POST /api/stacks/{orgName}/{projectName}/{stackName}/decrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/deployments/settings/encrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/encrypt`

### stacks (11)

- `DELETE /api/stacks/{orgName}/{projectName}/{stackName}`
- `GET /api/stacks/{orgName}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/logs`
- `HEAD /api/stacks/{orgName}/{projectName}`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/tags`
- `POST /api/stacks/{orgName}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/batch-decrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/batch-encrypt`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/rename`
- `PUT /api/stacks/{orgName}/{projectName}/{stackName}/config`

### state_io (2)

- `GET /api/stacks/{orgName}/{projectName}/{stackName}/export`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/import`

### update_create (8)

- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/latest`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}/contents/file/{path:.*}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}/contents/files`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update`

### update_lifecycle (22)

- `GET /api/preview/insights/{orgName}/accounts`
- `GET /api/preview/insights/{orgName}/accounts/{accountName}/scans/{scanId}/logs`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}`
- `GET /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/journalentries`
- `PATCH /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/journalentries`
- `POST /api/orgs/{orgName}/policypacks/{policyPackName}/{versionTag}/complete`
- `POST /api/registry/packages/{source}/{publisher}/{name}/versions/{version}/complete`
- `POST /api/registry/templates/{source}/{publisher}/{name}/versions/{version}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/deployments/{deploymentId}/cancel`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/destroy/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/preview/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/refresh/{updateID}/complete`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}`
- `POST /api/stacks/{orgName}/{projectName}/{stackName}/update/{updateID}/complete`

