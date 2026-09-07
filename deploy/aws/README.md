# AWS CloudFormation installer

See the standalone [AWS architecture guide](architecture.html) for a visual explanation of the
request path, database bootstrap, IAM boundaries, cost posture, and Marketplace work.

This template deploys two state-db replicas behind Amazon ECS Express Mode's managed HTTPS
Application Load Balancer. It creates an encrypted, private, Single-AZ PostgreSQL 17 RDS instance
with 80 GiB of gp3 storage by default and storage autoscaling up to 500 GiB. You can deploy into a
small dedicated VPC or supply an existing VPC and four subnets. The application task definition is
explicit so digest-pinned, single-architecture ARM64 and X86_64 candidates can both be tested;
`CpuArchitecture` must match the supplied image.

The long-running state-db tasks do not receive a database password. A one-off Fargate bootstrap task
uses RDS's managed administrator secret to create the `statedb` PostgreSQL login and grant
`rds_iam`; the application tasks then authenticate with short-lived tokens from their ECS task role.

## Before deployment

Released templates contain digest-pinned application and bootstrap images. The source template's
`__VERSION__` and `__IMAGE_DIGEST__` markers are replaced by `bin/package-aws-installer` and must
not be deployed as-is.

Create a bcrypt hash for the initial administrator password:

```sh
htpasswd -bnBC 12 "" 'a-long-unique-password' | cut -d: -f2
```

Create the stack with `CAPABILITY_IAM`. Stack creation can take 15–25 minutes, mostly for RDS. The
outputs include the console URL and the correct `pulumi login .../pulumi` command.

For a guided command-line test deployment, run:

```sh
deploy/aws/deploy.sh
```

The script defaults to the gated ARM64 candidate from commit `d186ef2`. It confirms the AWS caller
and region, generates the break-glass bcrypt hash locally, validates the template, displays a
CloudFormation change set, and pauses before execution. It also verifies that a GHCR image is
anonymously pullable because the current stack does not inject third-party registry credentials.
For test-stack compatibility, the script sets automated backup retention to one day. Override it
with `STATEDB_DB_BACKUP_RETENTION_DAYS`; the standalone template defaults to seven days.
Run `deploy/aws/deploy.sh --help` for image, network, database, and non-interactive overrides.

To delete the test stack after validation, use the same profile, region, and stack name:

```sh
deploy/aws/teardown.sh
```

Teardown deletes the live resources after confirmation. The database's final RDS snapshot remains
and continues to incur snapshot-storage charges until you delete it separately.

## Topology and cost posture

- Two 0.25-vCPU, 512-MiB Fargate tasks run at all times and may scale to four.
- Express Mode owns HTTPS, the ALB, target groups, alarms, and autoscaling. The stack owns the
  application task definition and log group so it can select the container CPU architecture.
- Express Mode creates and manages the load balancer security group. The stack adds a separate task
  security group so PostgreSQL can identify application and bootstrap tasks without a CIDR rule.
- RDS uses isolated subnets, is never public, and snapshots on stack deletion or replacement. The
  template retains seven days of automated backups; the guided test script requests one day.
- At the default 80 GiB, RDS gp3 includes 3,000 IOPS and 125 MiB/s. The template deliberately does
  not provision extra IOPS or throughput; PostgreSQL only supports those gp3 settings at 400 GiB.
- RDS storage can autoscale but cannot shrink. Never reduce `DbAllocatedStorage` after stack
  creation; raise it only when deliberately increasing the storage floor.
- The dedicated VPC has no NAT Gateway. Its application subnets are public, and the one-off
  bootstrap task receives a public IP so it can pull its image, publish logs, and retrieve the
  RDS-managed secret while reaching the private database through the VPC.

Single-AZ RDS is the lowest predictable always-on baseline. Multi-AZ should be the first production
availability upgrade. Aurora Serverless v2 is intentionally not hidden behind a condition in this
template: it deserves a separate profile because it is cheaper only when the application pool
actually becomes idle long enough to pause.

## Network modes

`NetworkMode=CreateNew` is the default. It creates only the network resources that this deployment
needs: one VPC, two public application subnets, two isolated database subnets, one internet gateway,
and one public route table. It does not create a NAT Gateway, VPC endpoints, a network ACL, or a
private application route table. Set `VpcCidr` and all four subnet CIDRs before creation. Choose a
range that does not overlap networks that you might connect through VPC peering, Transit Gateway,
or VPN. The stack outputs the VPC, subnet, and security-group IDs so another infrastructure stack
can consume them.

`NetworkMode=UseExisting` creates none of those VPC, subnet, route, or gateway resources. Supply:

- one VPC with DNS support enabled;
- two application subnets in different Availability Zones;
- two private database subnets in different Availability Zones; and
- `ApplicationSubnetType=Public` or `Private`, matching the application subnet routes.

The two pairs do not have to be four unique subnets. A private deployment can reuse the same two
subnet IDs for application and database placement if that matches the existing network policy;
the stack's security groups still restrict PostgreSQL to state-db tasks.

For public application subnets, each subnet needs a route to an attached internet gateway. Express
Mode and the bootstrap task assign public IP addresses. For private application subnets, select
`ApplicationSubnetType=Private`. The bootstrap task does not receive a public IP, and Express Mode
creates an internal load balancer. The existing network must then provide outbound access through a
NAT Gateway or the required VPC endpoints for the selected ECR registry, Amazon S3 image layers,
CloudWatch Logs, and Secrets Manager. The template deliberately does not alter existing route
tables, gateways, endpoints, network ACLs, or DNS settings.

Treat the network mode, VPC, and subnet parameters as creation-time choices. Changing them can
replace security groups, the DB subnet group, or the RDS instance. Always inspect a CloudFormation
change set first; the database has snapshot-on-replacement protection, but replacement still causes
an endpoint change and downtime.

CloudFormation checks that all five existing-network identifiers are present. AWS then validates
that the subnets exist, belong to the selected VPC, and satisfy the RDS and Express Mode placement
requirements. Check these facts before deployment so a mismatch fails before billable resources
are useful. The first Express service in a VPC also fixes the Availability Zones supported by its
managed load balancer; use application subnets in the same set of zones as any existing Express
service in that VPC.

See the AWS documentation for [Express Mode networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-work.html)
and [CloudFormation conditions](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/conditions-section-structure.html).

## Marketplace path

`ImageReference` and `BootstrapImageReference` are parameters so Marketplace packaging can replace
both with regional Marketplace-managed ECR images. AWS Marketplace requires every image used by a
delivery option, including the PostgreSQL bootstrap client, to be copied into its ECR repositories.

Before Marketplace submission, validate a limited-visibility buyer deployment in every advertised
region and document the IAM roles, database cost, ALB cost, backups, and deletion behavior.
