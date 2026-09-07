# RDS IAM SigV4 implementation audit

Date: 2026-08-31

## Verdict

The source-local signer is correct for the supported RDS endpoint shape. I found
no discrepancy in its canonical request, query encoding and ordering, signing
key derivation, temporary-credential handling, or returned token shape. Its
fixed signatures agree with the AWS SDK for Java v2.54.7 vectors recorded in
the tests, and a local AWS CLI 2.22.26 comparison independently confirmed the
special-character encoding, session-token placement, authority/path shape,
and query fields.

The audit initially found one low-severity operational gap outside the signing
calculation: `EcsTaskCredentialsProvider` refreshed cached task credentials
only when fewer than five minutes remained, while the generated token advertises
a 15-minute expiry. The implementation now shares the token-lifetime constant
with the ECS provider and refreshes credentials at that full 15-minute boundary.

## Sources and comparison method

I compared the uncommitted
[`RdsIamAuthenticationTokenGenerator`](../../modules/app/src/main/java/app/statedb/app/database/RdsIamAuthenticationTokenGenerator.java),
[`EcsTaskCredentialsProvider`](../../modules/app/src/main/java/app/statedb/app/database/EcsTaskCredentialsProvider.java),
and their tests against these sources:

- AWS's RDS-specific manual Java implementation, which defines `GET`, `/`,
  `Action=connect`, `DBUser`, `rds-db`, `host`, 900 seconds, the empty payload,
  the HMAC chain, and the scheme-less `host:port/?...` result
  ([RDS User Guide manual implementation](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.Connecting.Java.html)).
- The open-source AWS SDK for Java v1 RDS utility, which constructs the same
  request and removes the synthetic HTTP scheme
  ([`RdsIamAuthTokenGenerator.java`](https://github.com/aws/aws-sdk-java/blob/master/aws-java-sdk-rds/src/main/java/com/amazonaws/services/rds/auth/RdsIamAuthTokenGenerator.java)).
- Botocore/AWS CLI's RDS helper and generic SigV4 query signer, including
  temporary credentials, canonical-query ordering, request hashing, and
  signing-key derivation
  ([RDS helper](https://github.com/boto/botocore/blob/develop/botocore/signers.py),
  [`SigV4Auth` and `SigV4QueryAuth`](https://github.com/boto/botocore/blob/develop/botocore/auth.py)).
- The open-source AWS SDK for Go v2 RDS helper
  ([`feature/rds/auth/connect.go`](https://github.com/aws/aws-sdk-go-v2/blob/main/feature/rds/auth/connect.go)).
- The open-source AWS SDK for .NET RDS helper, especially its session-token and
  credential-lifetime behavior
  ([`RDSAuthTokenGenerator.cs`](https://github.com/aws/aws-sdk-net/blob/main/sdk/src/Services/RDS/Custom/Util/RDSAuthTokenGenerator.cs)).
- AWS's independent no-SDK Java sample and the mature MinIO Java signer as
  cross-checks for the pure-JDK SHA-256/HMAC structure. The MinIO presigner is
  S3-specific, so its `UNSIGNED-PAYLOAD` choice is deliberately not applicable
  to RDS
  ([AWS no-SDK `AWSSigner.java`](https://github.com/aws-samples/sigv4-signing-examples/blob/main/no-sdk/java/AWSSigner.java),
  [MinIO `Signer.java`](https://github.com/minio/minio-java/blob/master/api/src/main/java/io/minio/Signer.java)).
- AWS's normative SigV4 encoding and canonical-request guidance
  ([IAM SigV4 request signing](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)).

The local CLI comparison used fake published example credentials, a temporary
session token containing `+/= `, and username `name +/%~é`. The CLI returned
`database.example.com:5432/?`, encoded the username as
`name%20%2B%2F%25~%C3%A9`, encoded the session token as
`token%2B%2F%3D%20value`, and emitted the same required fields. The CLI's display
order puts `X-Amz-SignedHeaders` before `X-Amz-Security-Token`; state-db emits
canonical sorted order. Display order is not semantically significant because
both signers sort the encoded pairs for the canonical request.

## Confirmed behavior

| Area | State-db behavior | Confirmation |
| --- | --- | --- |
| Request target | `GET`, canonical path `/`, `Action=connect`, `DBUser` | Matches the RDS manual example, Java v1, Go v2, .NET, and botocore helpers. |
| Scope | `yyyyMMdd/region/rds-db/aws4_request` | Matches all AWS RDS implementations. |
| Algorithm | `AWS4-HMAC-SHA256` | Matches AWS SigV4 and all compared implementations. |
| Time | Injected `Clock`; UTC `yyyyMMdd` and `yyyyMMdd'T'HHmmss'Z'` | Correctly avoids the default-time-zone weakness in the old manual documentation sample. Tests use a fixed UTC clock. |
| Expiry | `X-Amz-Expires=900` | Matches Java v1's 15 minutes and Go/.NET/botocore's 900 seconds. |
| Host | Canonical `host:<lowercase-host>:<port>`; result begins `<host>:<port>/?` | RDS's manual example includes the port in both places and removes the scheme. Lowercasing DNS host names is semantically safe and agrees with the fixed AWS SDK vectors. |
| Payload | SHA-256 of the empty byte string, `e3b0...b855` | The RDS manual example hashes an empty payload. Botocore's generic query signer inherits empty-body hashing; only its S3-specific query signer substitutes `UNSIGNED-PAYLOAD`. |
| Percent encoding | UTF-8 bytes; only `A-Z a-z 0-9 - . _ ~` remain; space is `%20`; uppercase hex; `/` encoded | Exact match for AWS's `UriEncode` rules. The special-character test and local AWS CLI comparison cover the important divergences from form encoding. |
| Query ordering | Encode key/value first, then sort by encoded key and encoded value | AWS requires sorting after encoding. Botocore explicitly sorts encoded `(key, value)` pairs, including values for repeated keys. |
| Temporary credentials | Adds encoded `X-Amz-Security-Token` before calculating the signature | AWS requires the session token in the query for temporary credentials. Botocore and .NET include it in the signed query. The ECS integration test proves the token reaches the signer. |
| Signing key | HMAC `AWS4 + secret` -> date -> region -> `rds-db` -> `aws4_request`; final HMAC over the string to sign | Exact match for AWS's RDS manual code, botocore, AWS's no-SDK sample, and MinIO's independent implementation. |
| Result shape | `<lowercase-host>:<port>/?<signed-query>` without `https://` | Matches AWS Java v1, botocore/AWS CLI, .NET, and the RDS manual example. This also avoids the current Go v2 helper's reported missing-slash bug ([aws/aws-sdk-go-v2#3365](https://github.com/aws/aws-sdk-go-v2/issues/3365)). |
| Failure behavior | Invalid request data is rejected before credential resolution; credential-provider exceptions propagate unchanged; impossible JCA lookup failures become `IllegalStateException` | The ordering is deterministic and covered by focused tests. Go likewise returns credential retrieval failures directly. The extra validation is source-local policy rather than part of SigV4. |

## Test-vector assessment

The fixed basic-credential token is a strong golden test: it asserts the entire
result and the signature
`c07da4600988c5d1be3f6dd86704bed69ddaa18f2f2a447598cb6d41f4477876`.
The test records that it was generated independently by AWS SDK for Java
v2.54.7 with the same fixed clock. The temporary-credential and alternate-port
signatures add independent coverage of the two inputs most likely to be omitted
from the canonical request.

The encoding test checks the exact rendered query rather than only decoded
values, so it distinguishes `%20` from `+`, uppercase from lowercase escapes,
preservation of `~`, UTF-8 byte encoding, encoded credential slashes, and
canonical display order. The live local AWS CLI comparison independently
confirmed that exact username and session-token encoding.

One auditability improvement remains: the repository does not contain the
small AWS SDK vector-generation program or a captured CLI fixture. The comments
identify the SDK version and fixed clock, but a future reviewer cannot regenerate
the signatures from a checked-in tool. This does not undermine the current
agreement; it is a provenance/reproducibility limitation.

## JDBC pooling and credential-provider lifecycle

The call frequency is verified from the exact framework versions used here,
Quarkus 3.38.3 and Agroal 3.2.1. Quarkus installs
`AgroalVaultCredentialsProviderPassword` as the connection-factory credential;
its `asProperties()` method invokes `CredentialsProvider#getCredentialsAsync`
([Quarkus adapter](https://github.com/quarkusio/quarkus/blob/3.38.3/extensions/agroal/runtime/src/main/java/io/quarkus/agroal/runtime/AgroalVaultCredentialsProviderPassword.java),
[Quarkus datasource wiring](https://github.com/quarkusio/quarkus/blob/3.38.3/extensions/agroal/runtime/src/main/java/io/quarkus/agroal/runtime/DataSources.java)).
Agroal converts that credential to JDBC properties from
`ConnectionFactory#createConnection`, immediately before the JDBC driver opens
a physical connection. In contrast, pool acquisition takes an existing
`ConnectionHandler` from the local/shared cache and does not call the connection
factory
([Agroal `ConnectionFactory`](https://github.com/agroal/agroal/blob/3.2.1/agroal-pool/src/main/java/io/agroal/pool/ConnectionFactory.java),
[Agroal `ConnectionPool`](https://github.com/agroal/agroal/blob/3.2.1/agroal-pool/src/main/java/io/agroal/pool/ConnectionPool.java)).
Consequently, state-db generates a token only when Agroal creates a physical
JDBC connection, not for a pool checkout, transaction, statement, or query.
Changing credentials affects future physical connections; it does not
reauthenticate sessions already in the pool.

Quarkus maps `quarkus.datasource.jdbc.max-lifetime` directly to Agroal's pool
`maxLifetime`. Agroal schedules a graceful flush for each connection when it is
created: an idle connection is destroyed at the deadline, while an active one
is marked for retirement and destroyed when returned. The setting is therefore
a connection-recycling policy, not a password/token cache
([Quarkus configuration mapping](https://github.com/quarkusio/quarkus/blob/3.38.3/extensions/agroal/runtime/src/main/java/io/quarkus/agroal/runtime/DataSources.java),
[Agroal lifetime task](https://github.com/agroal/agroal/blob/3.2.1/agroal-pool/src/main/java/io/agroal/pool/ConnectionPool.java)).

The configured 10-minute lifetime is not required for correctness by the
15-minute RDS token expiry. AWS states that an RDS IAM token is required only
to establish a connection and does not determine how long the established
connection may remain open
([AWS Database Blog](https://aws.amazon.com/blogs/database/securely-connect-to-amazon-rds-for-postgresql-with-aws-session-manager-and-iam-authentication/)).
Because this provider generates a fresh token at each physical connection
attempt, even a connection created after an older token's 15-minute window gets
a new token. The 10-minute value deliberately exercises credential refresh and
bounds connection age, but it also adds periodic connection/authentication
churn; it should be retained or tuned as an operational policy rather than as a
token-expiry safety margin. This RDS-specific conclusion is narrower than
Quarkus's generic guidance for leased database credentials, which recommends
recycling connections before those credentials expire
([Quarkus credentials-provider guide](https://quarkus.io/guides/credentials-provider/)).

## Discrepancies and residual uncertainty

### Effective lifetime of tokens signed with near-expiry ECS credentials (resolved)

The ECS provider and signer now share one 15-minute lifetime constant. Cached
credentials are reused only when their expiry is strictly after the full token
lifetime. Deterministic tests cover both reuse just beyond the boundary and a
refresh exactly at it. State-db still generates a fresh token for every new JDBC
connection; established connections do not depend on token validity.

### Validation is intentionally narrower than generic SDKs

State-db accepts ASCII DNS names without a trailing dot, ports 1-65535,
nonblank/control-free usernames, conventional lowercase region strings, and
nonblank credentials. AWS SDK helpers generally rely more heavily on URI types
and downstream signing code; the .NET helper trims host/user and even permits
port zero. The state-db restrictions fit actual RDS endpoint/port/region inputs
and catch configuration errors earlier, but they are application policy, not a
SigV4 requirement. The main residual compatibility uncertainty is an unusual
quoted PostgreSQL role name containing Unicode whitespace or an ISO control
character; the signer rejects it even if PostgreSQL could represent it.

### No live RDS verification

The comparison covers official source, deterministic AWS SDK signatures, and
local AWS CLI output, but it does not authenticate to a live RDS or Aurora
instance. A live test would validate service acceptance and IAM/database
configuration together, not reveal a known mismatch in the signing algorithm.

## Conclusion

No signer fix is indicated. The implementation is a faithful, narrow RDS SigV4
query signer and is materially better than copying a generic presigner because
its service-specific contract is visible and testable. The ECS refresh window
now matches the 15-minute token lifetime. Consider checking in the vector
generator if long-term test provenance matters.
