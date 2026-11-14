# Console

The console lets users inspect Pulumi state and runs, manage credentials, and
administer their organizations. Infrastructure changes still go through the
Pulumi CLI; the console's only writes are operational ones such as tokens,
memberships, unlocks, compaction, and deleting an empty stack.

The React application lives in [modules/web](../modules/web/). TanStack Router
owns navigation, TanStack Query owns server queries, and shared components live
under `src/components`. The application calls the session-authenticated console
API under `/api`.

## URL space

The [router](../modules/web/src/router.tsx) defines the exact path set. The root
namespace is a compatibility layer for links that the Pulumi CLI prints, and
every console screen lives under `/console`. The CLI builds console links by
replacing the backend URL's path, so they always arrive at the host root in the
shape `/{org}/...`. Keeping product screens under one prefix means a new console
route can never collide with an organization name.

| Root path | Printed by the CLI for | Redirect target |
| --- | --- | --- |
| `/{org}/{project}/{stack}` | `pulumi console`, stack listings | `/console/{org}/{project}/{stack}` |
| `/{org}/{project}/{stack}/updates/{version}` | `up`, `refresh`, `destroy` | same path under `/console` |
| `/{org}/{project}/{stack}/previews/{updateId}` | `preview` | same path under `/console` |
| `/account/tokens`, `/user/settings/tokens` | access-token hints from different CLI versions | `/console/account/tokens` |

`/` redirects to `/console`. A version that is not an integer, a preview ID that
is not a UUID, and any other root path render the not-found page. Redirects do
not check that the target exists; the target screen returns its own not-found
state. `/cli-login` and `/welcome/cli` are server routes in `CliLoginResource`
for browser-based `pulumi login`, not SPA routes.

Because those root shapes start with an organization name, organization creation
rejects a fixed set of reserved names: `api`, `pulumi`, `tofu`, `console`,
`assets`, `cli-login`, `welcome`, `account`, `user`, and `favicon.ico`
(`AdminOrganizationsResource`). The set covers protocol and infrastructure
prefixes only and does not grow with the console's own routes.

For a self-hosted backend, the CLI prints these links only when
`PULUMI_CONSOLE_DOMAIN` names the console host. Pulumi's `CloudConsoleURL`
otherwise derives a console host only from an `api.` hostname or
`localhost:8080`, and omits the link for anything else. The empty stack list and
the tokens page show the export line next to `pulumi login`.

## Console routes

| Route | Screen |
| --- | --- |
| `/console` | Stack inventory, narrowed by the organization switcher |
| `/console/login` | Sign-in, outside the authenticated shell |
| `/console/{org}`, `/console/{org}/{project}` | Organization and project stack lists |
| `/console/{org}/{project}/{stack}` | Stack detail; `?tab=outputs`, `activity`, or `settings` selects a tab |
| `.../updates/{version}`, `.../previews/{updateId}` | Run detail |
| `/console/account/tokens` | The user's CLI tokens |
| `/console/integrations` | GitHub App and organization connections |
| `/console/admin/tokens` | Service accounts |
| `/console/admin/orgs/{org}` | Organization members and guest grants |
| `/console/admin/providers`, `orgs`, `users`, `operations` | Site administration |

Applied runs are addressed by their integer version. Previews use the update ID,
because a dry run does not consume a version.

Only providers, organizations, users, and operations sit under the site-admin
route wrapper. The members, service-account, and integrations routes also admit
organization owners, so they live outside it despite their paths; other users
are redirected to `/console` or `/console/{org}`. The organization switcher
lists every organization for a site administrator, because that authority comes
from the `STATEDB_ADMIN_SUBJECTS` allow-list rather than from memberships.

These client-side guards only shape navigation. `WorkspacesResource` supplies
inventory, stack detail, and export, `ConsoleRunsResource` supplies runs and
their events, and each console resource enforces authorization on the server.

## Stack and run data

Stack detail has resources, outputs, activity, and settings tabs. The resource
view shows inputs, outputs, and dependencies for each resource, and the activity
view links to individual runs.

The console never decrypts state. It renders Pulumi secret values as a masked
badge and points users to `pulumi stack output --show-secrets` for deliberate
plaintext. [pulumi-secrets.ts](../modules/web/src/lib/pulumi-secrets.ts) mirrors
the server's `SecretSentinel` and also masks the encrypted config shapes
(`secure` and `ciphertext`).

Run detail combines status, attribution, configuration, diagnostics, resource
changes, and stack outputs in log, changes, and configuration tabs. An
in-progress run polls every three seconds and stops at a terminal status. The
event log pages by sequence number through the `after` cursor, 500 events per
page. `UpdateEventReader` serves both live event rows and the compressed archive
of a finished run, so the console does not depend on which one holds a run's
events.

## Change interpretation

`RunChangeDeriver` converts engine events into the shared change model for
console views and Markdown reports, so renderers never reinterpret raw step
metadata. It reads `resourcePre`, `resOutputs`, and `resOpFailed` events in
sequence order and keeps one row per URN.

Property changes come from the most precise source available. A `detailedDiff`
wins over the coarse `diffs` list, and without either the deriver compares old
and new inputs. For a detailed diff, `inputDiff` selects old inputs or old
outputs as the baseline; new values always come from inputs, so provider-only
outputs do not appear as removed inputs. Paths under a resource's `hideDiffs`
are dropped, and paths with a `__`-prefixed segment are marked internal.
Replacement substeps collapse into one replacement.

Refresh needs care because Pulumi reports it in two ways. In a standalone
`pulumi refresh`, a final `update` or `delete` result supersedes the initial
`refresh` step, and so does a final `same` unless the refresh carried a property
diff. In `preview --refresh` and `up --refresh`, the refresh is
a preliminary phase, and a later `same` from the program diff does not relabel
it. A refresh step with a property diff counts as a change; one without a diff
is refresh activity. The changes tab lists real changes first and puts unchanged
resources and refresh activity behind one checkbox, so they cannot bury a
meaningful change. A completed refresh that found nothing reports "No drift
detected".

Stack output changes compare the old and new outputs of the
`pulumi:pulumi:Stack` resource. When the stack had no prior outputs, every
output is reported as unchanged.

The deriver redacts secrets before any renderer sees a value. `SecretSentinel`
detects secrets structurally: it recognizes Pulumi's secret wrapper, output-value
wrappers marked `secret: true` (whose inner value is plaintext), and treats an
unrecognized signature as secret. A property name never decides whether a value
is secret. The [report package](../modules/persistence/src/main/java/app/statedb/persistence/report/)
and its tests own these rules.

## Management surfaces

| Surface | Authority and behavior |
| --- | --- |
| Account tokens | Users mint, list, and revoke their own CLI tokens |
| Organization members | Owners and site administrators manage human members and inspect guest grants |
| Service accounts | Owners and site administrators mint and revoke organization-scoped CI credentials |
| Integrations | Site administrators create the deployment's GitHub App; owners connect installations |
| Providers, users, organizations | Site administrators |
| Operations | Site administrators inspect live locks and compaction jobs and force-unlock stacks |

Membership is checked on every request and tokens belong to the account, so
removing a member revokes that user's access to the organization's stacks on
their next request while their token keeps working for other organizations. The
server refuses to remove or demote an organization's last owner
(`OrgMembershipWriter`). An organization can be deleted only when it has no
stacks, because deleting it cascades to everything it owns. Service-account
profiles and lifecycle are described in [Authentication and authorization](auth.md).

Force-unlock sends the lock ID the operator saw. If that lease has already been
released or replaced, the API returns 409 instead of cancelling a newer update.
On success it removes the lock and cancels the update that held it.

The stack settings tab shows metadata, lock state, and compaction state. For site
administrators it also offers force-unlock, compaction, and stack deletion.
Deletion goes through `StackDeleter`, which removes the stack's backend state
and history but not its infrastructure, rejects stacks that still have resources
or an active update, and remains subject to Cedar `stack:Delete`.

The organization members page lists guest grants read-only. The console has no
controls to create or revoke grants or to change stack visibility;
[Stack visibility and grants](stack-sharing.md) describes that model.

## Errors and verification

The API returns authorization and validation errors to the caller. On a 401 the
client returns the browser to login. A stack the user cannot read returns 404,
the same response as a stack that does not exist and consistent with its absence
from the filtered inventory.

Route and component tests live beside the web source, and application tests
exercise the API and authorization rules. `./dev check` runs the web and backend
verification in the isolated build environment.
