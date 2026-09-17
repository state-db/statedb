# AWS-native PostgreSQL bootstrap for CloudFormation packaging

Research date: 2026-08-28

## Question

Can state-db replace its Lambda-backed CloudFormation custom resource for a
one-off Fargate PostgreSQL bootstrap task with a first-party AWS resource? The
bootstrap must create a least-privilege application role, grant `rds_iam`, and
install database and schema privileges before the ECS service starts.

## Short answer

The SQL bootstrap remains necessary, but the Lambda custom resource does not.
`AWS::RDS::DBInstance` can create the database and configure its master user,
but it cannot create PostgreSQL roles or issue schema grants. AWS documents the
required PostgreSQL operation as connecting with the master user (or another
role with role-management privileges), creating a user, and granting
`rds_iam`.

For the managed installer, keep the current Lambda-backed custom resource unless
removing Lambda is itself a product requirement. It is the conventional
CloudFormation mechanism for an imperative operation and already provides the
create/update/delete response contract. Replacing it with Systems Manager
reduces owned runtime code but adds a less common association lifecycle,
additional IAM, and a documented drift-detection caveat.

If a Lambda-free package is required, use a custom `AWS::SSM::Document` plus a
no-schedule `AWS::SSM::Association`. The Automation document should call ECS
`RunTask`, wait for the task to reach `STOPPED`, inspect the bootstrap
container's exit code, and fail unless it is zero. Set
`WaitForSuccessTimeoutSeconds` so CloudFormation waits for the association and
fails the stack when bootstrap fails. Make the ECS Express service depend on
that association.

Also prototype `AWS::SSMQuickSetup::LifecycleAutomation`. Its CloudFormation
documentation explicitly says that it runs an SSM Automation document on
CREATE, UPDATE, and DELETE and replaces inline Lambda custom resources. It is
the closest first-party resource to the desired lifecycle. Do not make it the
default until an integration stack proves its update, delete,
rollback, timeout, and failure behavior. Quick Setup also has a smaller region
footprint than RDS, ECS, and Systems Manager generally, which would limit the
regions in which the product can be installed.

## RDS master IAM authentication reduces secrets, not SQL

`AWS::RDS::DBInstance` now supports
`MasterUserAuthenticationType: iam-db-auth` for RDS PostgreSQL. This is useful:
the bootstrap task can authenticate as the master database user with a
short-lived IAM token. Its task role needs `rds-db:connect` only for the exact
master username and the instance's `DbiResourceId`. This removes the master
password from the bootstrap path.

Set `EnableIAMDatabaseAuthentication: true` as well, because that is the RDS
property that enables mapping IAM principals to database accounts. Omit
`MasterUserPassword`, `ManageMasterUserPassword`, and `MasterUserSecret` for
the IAM-master design. Current CloudFormation and RDS API documentation does
not state that `ManageMasterUserPassword` and `iam-db-auth` are mutually
exclusive; it only states that `ManageMasterUserPassword` conflicts with an
explicit `MasterUserPassword`. However, managing a password is unnecessary
when the selected master authentication method is IAM. This exact property
combination should be covered by a deployment test because the feature is new.

This does **not** remove the need for an application database user. AWS
strongly recommends that applications not use the master user and instead use
a user with the minimum required privileges. The bootstrap must still execute
idempotent SQL equivalent to:

```sql
CREATE USER statedb_app;
GRANT rds_iam TO statedb_app;
-- database CONNECT, schema USAGE/CREATE, and object privileges/ownership
-- required by Flyway and the application follow here.
```

The application ECS task role then receives `rds-db:connect` only for
`statedb_app`; it never receives permission to connect as the master user. If
IAM master authentication is unavailable for a selected engine version or
region, fall back to `ManageMasterUserPassword: true` and allow only the
bootstrap task to read the managed secret.

## Three materially different conclusions

| Choice | What it removes | What remains | Conclusion |
| --- | --- | --- | --- |
| Run the application as an IAM-authenticated master user | The separate SQL bootstrap and the master password | A long-lived application with master database privileges | Reject. It is the only direct way that `MasterUserAuthenticationType: iam-db-auth` removes bootstrap, and AWS explicitly recommends against using the master user in applications. |
| SSM Automation plus Association | The Lambda function and custom-resource response protocol | Our idempotent SQL and one-off Fargate bootstrap task | Viable Lambda-free alternative, but more novel and operationally indirect. Prototype before adoption. |
| Current Lambda-backed custom resource | Nothing; it owns orchestration and CloudFormation responses | Our idempotent SQL and one-off Fargate bootstrap task | Conventional and appropriate for the MVP. Keep it unless Lambda removal is a stated requirement. |

## Recommended CloudFormation lifecycle

1. Resolve either a stack-owned VPC and subnet set or supplied existing-network
   identifiers. Then create the RDS PostgreSQL instance, security groups, task
   definition, and two task roles. The bootstrap task role may connect only as
   the master database user. The long-lived application role may connect only
   as the application database user.
2. Create an SSM Automation document. Use `aws:executeAwsApi` for ECS
   `RunTask`, extract the task ARN, use `aws:waitForAwsResourceProperty` with
   `DescribeTasks` until `lastStatus` is `STOPPED`, and assert that the
   essential bootstrap container exited successfully.
3. Run the Automation from a no-schedule `AWS::SSM::Association`, with a
   bounded `WaitForSuccessTimeoutSeconds`. Give the Automation role narrowly
   scoped `ecs:RunTask`, `ecs:DescribeTasks`, and `iam:PassRole` permissions.
4. Make the ECS Express service depend on the successful association. Keep the
   SQL idempotent and take a PostgreSQL advisory lock so retries and stack
   updates are safe. CloudFormation rollback cannot undo SQL already committed.

The AWS-owned `AWS-ECSRunTask` Automation runbook is not sufficient by itself.
Its documented step calls `ecs:RunTask`; it does not document waiting for task
completion or checking the container exit code. A custom Automation document
is therefore required even though custom Lambda code is not.

## Alternatives considered

### Data API

The RDS Data API can execute DDL and DML, but it is an Aurora DB cluster API,
not an API for a normal `AWS::RDS::DBInstance`. `ExecuteStatement` also requires
a cluster ARN and a Secrets Manager secret ARN. It could replace the Fargate
bootstrap task only if the database choice changes to Aurora and an SSM or
other lifecycle orchestrator still invokes it. It is not a native
role-and-grants property and is not suitable for the low-cost RDS default.

### ECS Express deployment lifecycle hooks

ECS deployment lifecycle hooks run Lambda functions or pause a service
deployment until an external caller invokes `ContinueServiceDeployment`.
They are deployment hooks, not a CloudFormation database-provisioning
resource. A Lambda hook only moves the Lambda dependency. A pause hook needs
another external orchestrator and has weaker stack-level ownership.

Putting a bootstrap container inside every service task is technically
possible with an ECS `SUCCESS` container dependency, but it runs again during
scaling and every deployment. ECS task roles are task-level, not
container-level, so the application container would inherit the bootstrap
task's master access. That is a poor least-privilege trade for a managed installer.

### CloudFormation registry resource provider

A private or third-party CloudFormation resource type can model a database
role with create, update, delete, read, and list handlers. It is still custom
provider code, requires registration or activation in customer accounts and
regions, and uses Lambda-backed handlers. It adds more publishing and runtime
maintenance than the current custom resource and does not provide a native RDS
role primitive.

### Systems Manager interactive access

ECS Exec uses Systems Manager Session Manager to run commands in an existing
container. It is an interactive operations feature, runs commands as root, and
does not give CloudFormation a deterministic create/update failure contract.
It is not a packaging bootstrap mechanism.

### Secrets Manager rotation

RDS-managed master secrets and Secrets Manager rotation manage credentials,
not initial database roles, `rds_iam`, or schema privileges. Rotation uses
Lambda functions; alternating-user rotation also assumes users and an
administrative secret already exist. It cannot replace bootstrap.

### RDS Proxy

RDS Proxy supports end-to-end IAM authentication, so it can remove stored
database credentials from the application-to-proxy-to-database path. It still
requires the corresponding PostgreSQL user and privileges to exist. It adds a
paid proxy and does not replace role creation, so introduce it only for
connection pooling or failover requirements.

## When bootstrap can be removed

Bootstrap disappears only if the architecture changes one of its contracts:

- use the RDS master user in the application, contrary to AWS's explicit
  least-privilege guidance;
- require a customer-supplied database with a pre-created application role;
- require a manual post-install SQL step, weakening installer self-service;
  or
- give every application task enough database authority to run idempotent
  bootstrap on startup, exposing master capability to the long-lived task.

For a self-contained install with a freshly created RDS instance,
none of these is preferable. Keep a separate one-off bootstrap task.

## Decision

Do not replace the Lambda custom resource merely to
remove Lambda. The MVP uses RDS's managed master secret only in the one-off
bootstrap task and retains a separate IAM-authenticated PostgreSQL application
role at runtime. This avoids adding IAM-token tooling to the PostgreSQL client
image. The bootstrap execution role can read only that managed secret, and the
long-running application task role cannot read it. Revisit IAM authentication
for the RDS master after deployment tests prove its regional and lifecycle
behavior.

If a no-Lambda requirement appears, replace only the orchestration layer with
the SSM Automation plus Association pattern; keep the same one-off bootstrap
task and SQL. Treat its `WaitForSuccessTimeoutSeconds` drift-detection warning
as an accepted limitation and prove update, rollback, timeout, and nonzero-exit
behavior in integration tests.

Treat `AWS::SSMQuickSetup::LifecycleAutomation` as a promising follow-up, not
yet the default. If stack-level tests establish reliable wait, failure, and rollback
semantics in the supported regions, it can simplify the Association wiring further.

## Sources

- [AWS::RDS::DBInstance CloudFormation reference](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-rds-dbinstance.html)
- [RDS CreateDBInstance API](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBInstance.html)
- [RDS database authentication](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/database-authentication.html)
- [Creating a PostgreSQL account for IAM authentication](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.DBAccounts.html)
- [Creating an IAM policy for RDS database access](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.IAMPolicy.html)
- [RDS master user account privileges](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.MasterAccounts.html)
- [AWS::SSMQuickSetup::LifecycleAutomation](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssmquicksetup-lifecycleautomation.html)
- [Systems Manager Quick Setup availability](https://docs.aws.amazon.com/general/latest/gr/quick-setup.html)
- [AWS::SSM::Document](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-document.html)
- [AWS::SSM::Association](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-association.html)
- [Scheduling Automation with State Manager associations](https://docs.aws.amazon.com/systems-manager/latest/userguide/scheduling-automations-state-manager-associations.html)
- [Systems Manager Automation actions](https://docs.aws.amazon.com/systems-manager/latest/userguide/automation-actions.html)
- [Systems Manager `aws:waitForAwsResourceProperty`](https://docs.aws.amazon.com/systems-manager/latest/userguide/automation-action-waitForAwsResourceProperty.html)
- [AWS-ECSRunTask Automation runbook](https://docs.aws.amazon.com/systems-manager-automation-runbooks/latest/userguide/aws-run-ecs-task.html)
- [ECS RunTask API](https://docs.aws.amazon.com/AmazonECS/latest/APIReference/API_RunTask.html)
- [ECS deployment lifecycle hooks](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-lifecycle-hooks.html)
- [ECS container dependencies](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-ecs-taskdefinition-containerdependency.html)
- [ECS task IAM roles](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html)
- [RDS Data API](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/data-api.html)
- [Data API ExecuteStatement](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_ExecuteStatement.html)
- [CloudFormation registry concepts](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/registry-concepts.html)
- [CloudFormation resource type development](https://docs.aws.amazon.com/cloudformation-cli/latest/userguide/resource-types.html)
- [Secrets Manager rotation strategies](https://docs.aws.amazon.com/secretsmanager/latest/userguide/rotation-strategy.html)
- [RDS Proxy](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-proxy.html)
- [Adding database users for RDS Proxy](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-proxy-new-db-user.html)
- [ECS Exec](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-exec.html)
