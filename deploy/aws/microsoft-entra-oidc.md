# Configure Microsoft Entra ID authentication

This guide connects an AWS CloudFormation state-db installation to a customer's
Microsoft Entra ID tenant. It also explains how to grant site-administrator
access through the CloudFormation stack.

## Recommended configuration

Use a **single-tenant Web application** registration in the customer's Entra
tenant. Give the state-db provider a stable lowercase slug such as `microsoft`.
For a state-db origin of `https://statedb.test.com`, register this exact redirect
URI:

```text
https://statedb.test.com/api/auth/callback/microsoft
```

Configure state-db with:

```text
provider name:  microsoft
issuer URL:     https://login.microsoftonline.com/<DIRECTORY-TENANT-ID>/v2.0
client ID:      <APPLICATION-CLIENT-ID>
client secret:  <CLIENT-SECRET-VALUE>
scopes:         openid, profile, email
```

The provider name is part of the redirect path, so changing it changes the URI
that must be registered in Entra. State-db builds a Quarkus `web-app` tenant,
uses the configured issuer for OIDC discovery, and fixes the callback path to
`/api/auth/callback/<provider-name>`
([resolver](../../modules/console-api/src/main/java/app/statedb/consoleapi/auth/ProviderTenantConfigResolver.java)).
Microsoft requires a registered redirect URI for OIDC, requires HTTPS except
for localhost, and matches the path case-sensitively
([Microsoft redirect-URI rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url)).

## Create the Entra application

1. In the customer's Microsoft Entra admin center, switch to the customer's
   tenant. Go to **Entra ID > App registrations > New registration**.
2. Enter a recognizable name such as `state-db production`.
3. Select **Accounts in this organizational directory only**. This is the
   normal choice for a customer-owned state-db install: tenant members and
   tenant guests can sign in, but accounts from unrelated Entra directories
   cannot. Microsoft describes this option as a single-tenant line-of-business
   application
   ([registration reference](https://learn.microsoft.com/en-us/graph/auth-register-app-v2)).
4. Under **Redirect URI**, select the **Web** platform, not **Single-page
   application**, and enter the exact public callback shown above. The browser
   UI is an SPA, but state-db itself exchanges the authorization code and keeps
   tokens in a backend session; it is therefore a confidential Web client
   ([state-db BFF design](../../design/auth.md#console-spa--bff-with-quarkus-oidc),
   [Microsoft application types](https://learn.microsoft.com/en-us/entra/identity-platform/v2-app-types)).
5. Select **Register**. From **Overview**, copy both **Application (client) ID**
   and **Directory (tenant) ID**. Use the former as the state-db client ID and
   the latter in the issuer URL.
6. Go to **Certificates & secrets > Client secrets > New client secret**. Give
   it a purpose and expiry, create it, and copy the secret **Value** immediately;
   do not copy the secret ID. Microsoft displays the value only once and limits
   a client secret to at most 24 months
   ([credential instructions](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-credentials)).
7. Do not enable the implicit grant checkboxes. State-db uses authorization code
   flow, not an implicit browser-token flow. It also does not call Microsoft
   Graph, so it needs no Graph application permission, group claim, or app role.
   Tenant consent policy can still require an administrator to approve the
   basic sign-in request.

Use the tenant GUID in the issuer URL. Microsoft defines the v2 authority as
`https://login.microsoftonline.com/{tenant}/v2.0`; a tenant GUID or verified
tenant domain restricts sign-in to that tenant. Its discovery document is the
authority plus `/.well-known/openid-configuration`
([Microsoft OIDC discovery](https://learn.microsoft.com/en-us/entra/identity-platform/v2-protocols-oidc)).

Do not use `common`, `organizations`, or `consumers` for this setup. Those are
multi-tenant authorities whose discovery metadata contains a tenant-parameterized
issuer and requires tenant-aware issuer validation. State-db currently models a
provider as one fixed issuer/client pair and has no Microsoft-specific
multi-tenant validation policy. A single customer tenant is therefore the
narrow contract supported by the current implementation
([provider model](../../modules/persistence/src/main/java/app/statedb/persistence/identity/OidcProvider.java),
[Microsoft issuer-validation requirements](https://learn.microsoft.com/en-us/entra/identity-platform/access-tokens#validate-the-issuer)).

## Configure state-db

The AWS installer uses a console-managed provider. Sign in with the generated
break-glass credential while no provider is enabled, then open **Admin >
Providers** and add:

- name `microsoft`;
- the tenant-specific issuer URL;
- application client ID;
- client secret value;
- scopes `openid, profile, email`.

A provider created through the console starts disabled. Check its callback URL,
then enable it. Enabling the first provider immediately invalidates break-glass
sessions by design
([provider defaults](../../modules/console-api/src/main/java/app/statedb/consoleapi/provider/OidcProvidersResource.java),
[break-glass contract](../../design/auth.md#local-break-glass)). The provider secret is
write-only through the API, but is stored in PostgreSQL; database encryption at
rest and backup protection are operator responsibilities
([storage posture](../../design/architecture.md#database)).

The template does not expose the provider credentials as CloudFormation
parameters. This keeps the Entra client secret out of the CloudFormation
parameter history. State-db stores the console-managed secret in PostgreSQL.

## Claims, scopes, and administrator identity

Keep the default `openid profile email` scopes. State-db requires only the
verified `iss` and `sub` claims; a login missing either fails. It copies `email`
and `name` when present, but both database columns are nullable, so neither
claim is required for authentication
([provisioning hook](../../modules/console-api/src/main/java/app/statedb/consoleapi/auth/OidcUserProvisioner.java),
[user upsert](../../modules/persistence/src/main/java/app/statedb/persistence/identity/UserUpserter.java),
[schema](../../design/schema.sql)). `profile` normally supplies `name`; `email` requests
an email claim, but Microsoft explicitly says that the claim can still be absent.
These values are mutable display data and must not be used for authorization
([Microsoft OIDC scopes](https://learn.microsoft.com/en-us/entra/identity-platform/scopes-oidc),
[ID-token claims](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference)).

The checked-in state-db default does not include `offline_access`. Microsoft
requires that scope explicitly on its v2 endpoint before it returns a refresh
token. Consequently, operators should expect Entra reauthentication when the
token-backed console session can no longer be continued rather than assuming
the design's generic refresh-token support guarantees a Microsoft refresh token.
Adding `offline_access` is possible for a console-managed provider, but is not
part of the bootstrap provider's default and has no Microsoft-specific contract
test today
([scope default](../../modules/console-api/src/main/java/app/statedb/consoleapi/provider/OidcProvidersResource.java),
[Microsoft `offline_access` behavior](https://learn.microsoft.com/en-us/entra/identity-platform/scopes-oidc#the-offline_access-scope)).

Site administrators are configured separately in `STATEDB_ADMIN_SUBJECTS` as a
comma-separated list of exact `<iss>:<sub>` strings. For Entra, one entry looks
like:

```text
https://login.microsoftonline.com/<DIRECTORY-TENANT-ID>/v2.0:<OPAQUE-SUBJECT>
```

State-db does exact string comparison against `iss + ":" + sub`, reads the list
once at startup, and does not use email, UPN, Entra `oid`, groups, or app roles
for site-admin status
([admin allow-list](../../modules/persistence/src/main/java/app/statedb/persistence/identity/AdminAllowList.java)).
Microsoft defines `sub` as immutable but pairwise to the application client ID;
the same user gets a different `sub` in a different app registration. Replacing
the Entra app registration rather than rotating its secret therefore requires
new state-db identities and admin-subject entries
([Microsoft ID-token claims](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference)).

The reliable first-admin procedure is:

1. Configure the provider and let the intended administrator sign in once.
   This creates a non-admin `users` row even if the allow-list is not ready.
2. Read the exact `iss` and `sub` from PostgreSQL:

   ```sql
   SELECT iss, sub, email, last_login_at
   FROM users
   WHERE iss LIKE 'https://login.microsoftonline.com/%/v2.0'
   ORDER BY last_login_at DESC;
   ```

   An existing site admin can instead copy the canonical **Identity** value from
   **Admin > Users**
   ([admin-users UI](../../modules/web/src/routes/admin-users.tsx)).
3. Set the CloudFormation `AdminSubjects` parameter to the exact concatenated
   value. Do not substitute the user's email, UPN, object ID, or tenant ID for
   `sub`.

## Apply administrator subjects through CloudFormation

`AdminSubjects` accepts a comma-separated list. For example:

```text
https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0:AAAAABBBBBCCCCCDDDDD,https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0:EEEEFFFFFGGGGGHHHHH
```

For the guided installer, export the value and run an update:

```sh
export STATEDB_ADMIN_SUBJECTS='https://login.microsoftonline.com/<DIRECTORY-TENANT-ID>/v2.0:<OPAQUE-SUBJECT>'
deploy/aws/deploy.sh
```

If `STATEDB_ADMIN_SUBJECTS` is unset during a later guided update, the script
preserves the stack's existing value. Setting it to an empty string explicitly
clears the allow-list.

The deployment script passes that value as the CloudFormation `AdminSubjects`
parameter. The template maps it into the application container:

```yaml
- Name: STATEDB_ADMIN_SUBJECTS
  Value: !Ref AdminSubjects
```

Changing the parameter creates a new `AppTaskDefinition` revision. The ECS
Express service then rolls its tasks, and each new state-db process reads the
allow-list during startup. No database update grants site-administrator status.

When you update the stack in the CloudFormation console instead, select the
current template, edit **OIDC site administrator subjects**, and review the
change set before applying it. Preserve every administrator that should retain
access: the new comma-separated value replaces the complete allow-list.

After the rollout, sign in again and verify that **Admin** is available. Keep a
recovery route until that check succeeds. Enabling the first OIDC provider ends
existing break-glass sessions, so an incorrect subject can otherwise leave the
deployment without an OIDC site administrator.

The synthetic bootstrap identity `local:bootstrap:admin` remains a site admin
and owns any organization created with `STATEDB_BOOTSTRAP_ORG_NAME`; making an
OIDC subject a site admin does not rewrite that organization membership
([bootstrap provisioner](../../modules/persistence/src/main/java/app/statedb/persistence/bootstrap/BootstrapProvisioner.java)).

## Production and security checklist

- Put state-db behind HTTPS. Ensure the trusted ingress/load balancer preserves
  the public host and sends the public scheme, and enable
  `QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING=true` only when port 8080 is
  reachable exclusively through that proxy. Otherwise the generated
  `redirect_uri` can use the wrong scheme or authority. The current example Helm
  chart does not set this variable.
- Restrict who may authenticate if tenant-wide sign-in is too broad. In the
  Entra **Enterprise application**, set **Assignment required?** to **Yes** and
  assign the approved users or groups. Without required assignment, unassigned
  tenant users can still perform service-provider-initiated sign-in
  ([Microsoft access-management guidance](https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/what-is-access-management),
  [assignment procedure](https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/assign-user-or-group-access-portal)).
  Required assignment may also require tenant-wide admin consent under the
  tenant's consent policy.
- Apply the customer's Conditional Access and MFA policy to the enterprise
  application. State-db delegates authentication to Entra and does not implement
  a second MFA policy.
- Store the client secret in a deployment secret manager, protect PostgreSQL and
  its backups, record the expiration, and rotate before it expires. The
  environment-managed provider supports rotation by replacing the secret and
  redeploying; console-managed providers support a secret update.
- Microsoft recommends certificates or federated credentials instead of client
  secrets in production. State-db currently accepts only a client-secret string,
  so those stronger client-authentication methods are not yet available; this is
  an explicit product limitation
  ([Microsoft credential guidance](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-credentials),
  [state-db resolver](../../modules/console-api/src/main/java/app/statedb/consoleapi/auth/ProviderTenantConfigResolver.java)).
- Test with an assigned normal user and an unassigned user, test the exact HTTPS
  callback, verify the resulting `iss`/`sub`, and verify a fresh-browser
  `pulumi login https://statedb.test.com/pulumi` before removing installation
  access.

## Conclusion

For a customer-owned Microsoft identity provider, use one tenant-specific Entra
Web app registration and one state-db provider. The only mandatory identity
claims are `iss` and `sub`; `profile` and `email` improve the UI but do not grant
access. Entra controls who may authenticate, while state-db separately controls
site administration through the exact token identity in
`STATEDB_ADMIN_SUBJECTS` and organization access through its own membership and
policy model.
