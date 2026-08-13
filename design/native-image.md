# state-db: Native Image

Companion to [`architecture.md`](./architecture.md), [`auth.md`](./auth.md), and
[`release.md`](./release.md). This doc defines the shipped artifact: the Mandrel-compiled binary,
the constraints closed-world compilation puts on the application, and where reachability metadata
lives.

---

## Goals

- One production artifact: a Mandrel native binary. No JVM image is built or published.
- Cedar authorization runs in the binary through cedar-java's JNI binding to the Rust core.
- Absent reachability metadata fails closed — at build time where analysis can see it, at startup
  otherwise. It never surfaces as a wrong authorization decision.
- Reachability declarations live beside the code that causes them, with one exception for
  dependency internals.

## Non-goals

- Proving reachability completeness. The drift check observes exercised paths; untested branches
  are held by the runtime boundaries below.
- Owning cedar-java's native-image support. The registrations and a native-aware library loader
  belong upstream.
- Cross-compilation. Native Image builds on the target architecture; the release gates one
  candidate per architecture and assembles a manifest from them.

---

## Constraints

| Constraint | Reason |
| --- | --- |
| Runtime image extracts `libcedar_java_ffi.so`, sets `CEDAR_JAVA_FFI_LIB` to its absolute path | cedar-java's JNE path fails with `Unsupported executable resource protocol [resource]`; registering the embedded resource does not change that URL protocol. With the variable set, `LibraryLoader` calls `System.load`. |
| glibc runtime base | Published Cedar Linux libraries target `*-unknown-linux-gnu`. Alpine and musl are not interchangeable. |
| `com.cedarpolicy`, `com.github.luben.zstd`, `PolicySetCache`, `Authorizer`, `RawApiTokens` initialize at run time | Library handles, cleaners, caches, and entropy captured into the image heap fail at startup. zstd-jni loads its `.so` from a static initializer, so build-time init bakes in a "loaded" flag for a library the process never opened. |
| `quarkus.native.march=compatibility` | The GraalVM x64 default can emit `x86-64-v3`, which refuses to start on older hosts. |

Both JNI libraries are lifted out of their jars at build time; a loader's extract-to-temp path has
no jar to read in a native image and needs a writable filesystem. zstd's basename must survive the
move — its loader asks for it by `System.loadLibrary("zstd-jni-<version>")`.

Measured on arm64, both containers limited to 512 MiB: startup 0.379 s vs 2.620 s, idle container
memory ~68 vs ~278 MiB, image size 51.2 vs 222.6 MB.

---

## Reachability metadata ownership

Native Image needs metadata for dynamic accesses closed-world analysis cannot resolve. Each kind
has one home:

| Dynamic access | Contract |
| --- | --- |
| Panache `.project(Foo.class)`, HQL `select new pkg.Foo(...)` | `ProjectionReflection`; the target is named only inside an HQL string, so augmentation cannot see it |
| Hibernate entities, REST payload types, CDI/interceptor reflection | Quarkus build-time discovery; nothing to declare |
| Computed resource names | `@RegisterResources` beside the resource owner |
| Runtime reflection, `Class.forName`, `MethodHandles`, proxies, dynamic Jackson codecs | Narrow source-local registration plus a test that reaches it |
| Cedar JNI callbacks, private Cedar/Jackson types | Version-pinned central JSON |

Central files live at
`modules/app/src/main/resources/META-INF/native-image/app.statedb/state-db-<dependency>-<version>/reachability-metadata.json`,
each tracking its dependency's version property in `modules/pom.xml`.
`NativeReachabilityMetadataTest` fails if they diverge, if a registered type, member, or field no
longer exists, if a resource glob matches no production resource, or if an `app.statedb.*` type
appears in a central file.

cedar-java is pinned exactly. Every Cedar, Quarkus, Hibernate, Jackson, Mandrel, or JDK upgrade is
metadata-affecting and runs the full contract in the same change.

---

## Drift check

CI runs the comprehensive suite on the dev-container JDK, then re-runs `app.statedb.app.auth.*Test`
in a Surefire fork of the digest-pinned Mandrel builder's JVM with the tracing agent attached
(`-Pnative-agent-tests`). At `verify`, `bin/check-native-metadata-drift` merges the agent output,
canonicalizes it, keeps `com.cedarpolicy.*` observations plus exact production resources, and diffs
against `modules/app/src/test/native-image-agent-reviewed/reachability-metadata.json`. Any addition
or removal fails the build. `./bin/verify-native-metadata-contract` runs it locally; the
`native-image-contract` skill reviews a workspace diff for the same risks earlier.

- The authorization tests are the traced workload: they reach all 21 Cedar types in 3:37, where the
  whole suite takes 27:00 and observes only types Quarkus derives from the Jandex index. A broader
  workload would also make the baseline sensitive to unrelated test edits.
- Removals fail as loudly as additions — that is how the check notices a call path that stopped
  reaching Cedar.
- Canonicalization sorts and deduplicates arrays of objects only. `parameterTypes` and proxy
  interface lists are signatures: reordering them rewrites an observation into a method that does
  not exist and makes a real signature change compare equal.
- Agent output never reaches the build. `quarkus.native.agent-configuration-apply` stays off, so a
  changed test cannot change the binary.
- `bin/prepare-native-agent-jvm` extracts the agent JVM from the same digest-pinned builder image
  the native build uses. The agent ships only inside a GraalVM distribution and its output shape
  tracks the compiler version, so the two cannot drift apart.

### Boundaries the check does not hold

The check is scoped to `com.cedarpolicy.*` on exercised paths. It tracks neither application types
(Quarkus registers those) nor the Jackson and JDK types Cedar reaches through its serializers (the
central files register those). Four runtime boundaries cover the rest:

| Boundary | Catches |
| --- | --- |
| `--exact-reachability-metadata=com.cedarpolicy,app.statedb` | Unregistered access in those packages, at build time where analysis can prove it |
| `-XX:MissingRegistrationReportingMode=Exit` in the image `ENTRYPOINT` | Unregistered access at runtime; exits rather than degrading. In `ENTRYPOINT` because a `CMD` is silently replaced by `docker run <image> <args>` or a Kubernetes `args:` |
| Negative metadata build | A required Cedar registration removed from otherwise valid JSON. Compilation still succeeds because the target is dynamic, so `bin/verify-native-metadata-negative` requires the image to fail the Cedar readiness contract |
| `CedarAuthorizationReadinessCheck` | Libraries such as Jackson that surface absent metadata as their own functional exception rather than a GraalVM registration error |

---

## Operations

Readiness on management port 9000 (`/q/health/ready`) includes `CedarAuthorizationReadinessCheck`,
which loads the Rust library and executes a known allow and a known deny. A missing library, broken
JNI mapping, serialization failure, or unsafe decision keeps readiness down. Owner-allow and
viewer-deny for `org:ManageMembers` are policy invariants; a policy change updates the check
explicitly rather than silently weakening readiness.

Deployment posture: explicit memory limit, read-only root filesystem, writable `/tmp` tmpfs, the
image's numeric non-root user, `SIGTERM` with at least 10 seconds before `SIGKILL`.

Diagnosis: native crashes produce no Java heap dump, so start with container exit status and
stderr. `SIGQUIT` prints a native thread dump where the platform supports it. Compare
`/licenses/CEDAR-NATIVE-SHA256` with `/work/libcedar_java_ffi.so` on suspected architecture
mismatch or tampering. Registration exits point at reachability or JNI metadata; ordinary denials
point at policy or entity construction.

---

## Licensing and release evidence

cedar-java and its FFI crate are Apache-2.0, but its `uberJar` task omits the repository-root
`NOTICE` and `THIRD_PARTY_LICENSES.txt`. The version-matched upstream files are vendored under
`third_party/cedar-java/` and copied into `/licenses`; `bin/release-gate` verifies they are present.
Re-verified on every Cedar upgrade: the `.so` is a Rust `cdylib` built from cedar-policy and
transitive crates, so a scanner sees an opaque ELF, not the Cargo graph.

GraalVM CE is GPLv2 with the Classpath Exception, so native compilation does not affect state-db's
Elastic-2.0 licensing. Mandrel is a build stage, not a runtime layer. UBI Micro content is
redistributable under the UBI EULA; non-UBI RHEL packages are not added to the image.

Mandrel has no `--enable-sbom` (Oracle GraalVM only), so releases carry a CycloneDX Maven SBOM from
`bin/generate-java-sbom` plus a per-architecture container SBOM, both scanned during the run that
produces them.

`native.yml` is dispatched on demand because a run costs three native compilations. Consequence:
nothing rebuilds the digest-pinned base images on its own, and a released image stops receiving
base-image fixes until a rebuild is dispatched — a manual step in the release cadence.

`native.yml` and `bin/release` own the rest; see [`release.md`](./release.md).
