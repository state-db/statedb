---
name: native-image-contract
description: Review state-db workspace changes for Native Image reachability risks and guide contributors to the narrowest source-local, dependency-owned, or runtime-tested contract. Use when changing Java reflection, Panache or HQL projections, runtime-selected Jackson behavior, classpath resources, proxies, method handles, Class.forName, JNI, or native-image metadata.
---

# Native Image Contract

The drift check only sees `com.cedarpolicy.*` and production resources, so a missing projection
target or `Class.forName` produces no drift at all — it compiles, builds an image, and fails when
the path first runs. This review is the earlier check. See `design/native-image.md` for the
ownership table and the full limits.

## Workflow

Review the changed code only; do not inventory the whole repository.

1. Read `AGENTS.md` and `design/native-image.md`.
2. Inspect `git status --short` and the workspace diff. Preserve unrelated changes.
3. Find dynamic accesses in changed production code: Panache `.project(Type.class)` and HQL
   `select new pkg.Type(...)`; reflection, `Class.forName`, runtime member lookup, `MethodHandles`;
   dynamic proxies and runtime-selected Jackson codecs; computed resource names; `native` methods
   and JNI callbacks; any target selected from configuration or data.
4. Classify each against the ownership table in `design/native-image.md`, then apply the narrowest
   contract: projections go in `app.statedb.persistence.ProjectionReflection`, computed resources
   get a narrow `@RegisterResources`, and `app.statedb.*` types never go in the central Cedar JSON.
5. Report the call site, target, classification, required declaration, and missing test coverage.
   If nothing is at risk, say which changed files and patterns you checked.

## Verification

```sh
./bin/verify-native-metadata-contract
```

When drift fails, inspect
`modules/app/target/native-image-agent-review/reachability-metadata.json`, add the narrow contract
and a test that reaches it, then update the reviewed baseline explaining every addition or removal.
A removal usually means a call path stopped reaching Cedar, not that the metadata became
unnecessary. Never copy raw agent output into `src/main/resources` and never enable automatic
agent-output application.

Run Java and Maven commands only through `./bin/in-container`. Do not commit, push, or open a pull
request unless explicitly requested.
