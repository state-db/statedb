# Native image

The production artifact is a Mandrel-compiled executable in a UBI Micro
container; no JVM image is built or published. Native Image cannot
cross-compile, so the release workflow builds the amd64 and arm64 images on
matching runners and gates each one before assembling a manifest.

[Dockerfile.native](../modules/app/src/main/docker/Dockerfile.native) owns the
build and runtime layout. [application.yml](../modules/app/src/main/resources/application.yml)
owns the Quarkus native configuration.

## JNI and runtime constraints

Cedar authorization calls its Rust core through cedar-java's JNI binding, and
event archives use zstd-jni. The build lifts both shared libraries out of their
jars and ships them beside the executable. A loader's extract-to-temp path has
no jar to read in a native image and would need a writable filesystem.

| Constraint | Reason |
| --- | --- |
| `CEDAR_JAVA_FFI_LIB` points to `/work/libcedar_java_ffi.so` | cedar-java cannot load its embedded library through a native image's resource URL; with the variable set, its loader calls `System.load` on the path |
| The zstd library keeps its original basename in `/usr/lib64` | zstd-jni loads it with `System.loadLibrary("zstd-jni-<version>")` |
| glibc runtime base | The published Cedar Linux libraries target GNU libc; musl images cannot load them |
| Cedar, zstd, `PolicySetCache`, `Authorizer`, and `RawApiTokens` initialize at run time | Library handles, cleaners, caches, and random-number state captured in the image heap are invalid in the running process. zstd-jni loads its library from a static initializer, so build-time initialization would record a library as loaded that the process never opened |
| `quarkus.native.march=compatibility` | The x64 default can emit instructions that older hosts cannot run |

The container runs as numeric UID 1001 and supports a read-only root filesystem
with a writable `/tmp`. The entry point sets two runtime flags:

| Flag | Effect |
| --- | --- |
| `-XX:MissingRegistrationReportingMode=Exit` | Exits on an unregistered dynamic access instead of failing individual requests |
| `-XX:MaximumHeapSizePercent=60` | Leaves container memory for JNI libraries, thread stacks, and other native allocations |

Both flags live in `ENTRYPOINT` rather than `CMD`, because `docker run <image>
<args>` or a Kubernetes `args:` silently replaces `CMD`.

## Reachability ownership

Quarkus discovers entities, REST payloads, and CDI types at build time. Every
other dynamic access needs an explicit declaration near its owner:

| Dynamic access | Registration |
| --- | --- |
| Panache `.project(Foo.class)` and HQL `select new pkg.Foo(...)` | `ProjectionReflection`, because the target is named only inside a query string |
| Computed classpath resource names | `@RegisterResources` beside the resource owner |
| Runtime reflection, `Class.forName`, method handles, proxies, or runtime-selected Jackson codecs | Narrow source-local registration and a test that reaches it |
| Cedar JNI callbacks and private Cedar or Jackson types | Version-pinned central metadata |

Central metadata lives under
[app's native-image resources](../modules/app/src/main/resources/META-INF/native-image/app.statedb/),
one `state-db-<dependency>-<version>` directory per dependency.
`NativeReachabilityMetadataTest` fails when a directory's version differs from
the pinned dependency version, when a registered type or member does not exist, when a
resource pattern matches no production resource, or when an `app.statedb` type
appears in a central file. cedar-java is pinned exactly, and an upgrade of Cedar,
Quarkus, Hibernate, Jackson, Mandrel, or the JDK runs the native contract in the
same change.

## Drift and failure checks

`./dev native-contract` reruns the authorization tests and
`DatabaseCredentialsProviderTest` in the JVM of the digest-pinned Mandrel builder
image with the tracing agent attached. `bin/prepare-native-agent-jvm` extracts
that JVM from the same builder the native build uses, so agent output and
compiler cannot drift apart. `bin/check-native-metadata-drift` canonicalizes the
observations, keeps Cedar types and exact production resources, and compares
them with the reviewed baseline in `modules/app/src/test/native-image-agent-reviewed/`.
Additions and removals both fail; a removal means a call path stopped reaching Cedar.

Canonicalization sorts and deduplicates only arrays of objects. Parameter type
lists and proxy interface lists are signatures, and reordering them would turn
an observation into a method that does not exist.

Agent output never becomes build input; the compiler uses only the reviewed
declarations, so a changed test cannot change the binary. The drift check covers
exercised Cedar paths and selected resources, not every dynamic access in the
application. These runtime boundaries cover the rest:

| Boundary | Catches |
| --- | --- |
| `--exact-reachability-metadata=com.cedarpolicy,app.statedb` | Unregistered access in those packages, at build time where analysis can prove it |
| `MissingRegistrationReportingMode=Exit` | Unregistered access at run time |
| Negative metadata build | A required Cedar registration removed from otherwise valid metadata; `bin/verify-native-metadata-negative` requires that image to fail authorization readiness |
| `CedarAuthorizationReadinessCheck` | Failures a library reports as its own exception rather than a registration error |

`CedarAuthorizationReadinessCheck` loads the native library and evaluates a known
allow (owner) and a known deny (viewer) for `org:ManageMembers`. A missing
library, broken JNI mapping, serialization failure, or unexpected decision keeps
readiness down. These two decisions are policy invariants; a policy change that
affects them updates the check explicitly.

## Diagnosis

A native crash produces no Java heap dump, so start with the container exit
status and stderr. `SIGQUIT` prints a native thread dump where the platform
supports it. A registration exit points at reachability or JNI metadata, while an
ordinary denial points at policy or entity construction. On a suspected
architecture mismatch or tampering, compare `/licenses/CEDAR-NATIVE-SHA256` with
`/work/libcedar_java_ffi.so`.

## Licensing and release evidence

cedar-java's uber jar omits the upstream `NOTICE` and third-party license files,
so version-matched copies are vendored under `third_party/cedar-java/` and copied
into `/licenses`. The build verifies the Cedar library against the upstream
checksums and records its own checksum in the image. `bin/release-gate` checks
these files and runtime authorization behavior. GraalVM CE is licensed under
GPLv2 with the Classpath Exception, so native compilation does not change
state-db's AGPL-3.0-only license.

Mandrel cannot generate an embedded SBOM, so releases carry a CycloneDX Maven
SBOM from `bin/generate-java-sbom` plus a per-architecture container SBOM. The
native workflow runs only on manual dispatch, so the digest-pinned base images
receive updates only when someone dispatches a rebuild.

[Releases](release.md) describes how these candidates become a published
manifest. The [native-image contract skill](../.agents/skills/native-image-contract/SKILL.md)
provides the repository's change-review procedure.
