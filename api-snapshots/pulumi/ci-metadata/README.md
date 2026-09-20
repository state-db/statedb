# CI linkage metadata — captured shapes

Regression evidence for `design/pr-enrichment.md` ("Linkage: what the CLI
actually sends"). These files pin **which `metadata.environment` keys the
Pulumi CLI sends, and when** — the input that pull-request enrichment resolves
a PR from. They are not discovery material; the linkage rules were read from
Pulumi source first and these confirm them.

Only `POST /api/stacks/{org}/{project}/{stack}/preview` (and its
`update`/`destroy`/`refresh` siblings) carries the metadata bag. The rest of
the command's traffic is irrelevant here and is not copied.

Captured with Pulumi CLI `v3.259.0`, the version pinned in
`tools/pulumi/recorder/Dockerfile`.

## Shapes

| File | Shape | What it pins |
|---|---|---|
| `preview-local-no-ci.json` | plain local preview, no CI | **no `ci.*` keys at all** |
| `preview-ci-escape-hatch.json` | `PULUMI_CI_SYSTEM` escape hatch | all seven `ci.*` keys, values passed through verbatim |

`preview-local-no-ci.json` is a verbatim copy of
`tools/pulumi/recorder/fixtures/by-command/04-preview/0006_POST_..._preview.json`
from the committed baseline capture. It is the negative control: the recorder
container runs no CI and has no git repository at the project root, so the bag
carries `exec.kind`, `runtime.name`, `stack.environments`, `updatePlan` and the
`pulumi.*` keys — and no `ci.*`, `git.*` or `vcs.*`. This is the common case
for a developer's local preview, and the reason PR resolution cannot depend on
the CLI supplying a PR number.

`preview-ci-escape-hatch.json` was captured by command
`35-preview-ci-escape-hatch` in `tools/pulumi/recorder/entrypoint.sh`, which
runs an ordinary `pulumi preview` with:

```text
PULUMI_CI_SYSTEM=statedb-recorder
PULUMI_PR_NUMBER=4242
PULUMI_CI_PULL_REQUEST_SHA=0123456789abcdef0123456789abcdef01234567
PULUMI_CI_BUILD_ID=recorder-build-7
PULUMI_CI_BUILD_NUMBER=7
PULUMI_CI_BUILD_TYPE=pull_request
PULUMI_CI_BUILD_URL=https://ci.invalid/builds/7
```

and produces:

```json
"ci.build.id":     "recorder-build-7",
"ci.build.number": "7",
"ci.build.type":   "pull_request",
"ci.build.url":    "https://ci.invalid/builds/7",
"ci.pr.headSHA":   "0123456789abcdef0123456789abcdef01234567",
"ci.pr.number":    "4242",
"ci.system":       "statedb-recorder"
```

Three things this confirms, each of which the design depends on:

1. The escape hatch works with **no CI present**, on an unmodified CLI, driven
   entirely by environment variables. That is what makes explicit PR
   coordinates available to any pipeline on any host without a statedb-side
   endpoint.
2. `ci.system` takes the `PULUMI_CI_SYSTEM` value verbatim — it is not
   validated against a known-systems list. An arbitrary string matches the
   generic detector.
3. The values are **not** normalized by the recorder, so this fixture pins the
   key names and the value passthrough together. A CLI bump that renames a key
   or drops the generic detector changes this file.

Note also `pulumi.env.PULUMI_CI_SYSTEM: "set"` and its siblings: the CLI
enumerates every `PULUMI_`-prefixed variable into the bag as a presence flag.
That is a second, independent signal that the escape hatch was in use, but it
is a flag only — it carries no value.

## Not captured

**A GitHub Actions `pull_request` run.** This is the shape where
`ci.pr.number` and `ci.pr.headSHA` are populated by real detection, and where
`git.head` differs from `ci.pr.headSHA` because Actions checks out a synthetic
merge commit. Reproducing it needs a GitHub Actions runner and a repository
with an open pull request; the recorder cannot fabricate it, and a hand-written
file would be an assertion dressed as evidence. The design's claim about this
shape rests on Pulumi source (`sdk/go/common/util/ciutil/`), which states it
directly; see "Linkage: what the CLI actually sends" §3.

## Reproducing

The escape-hatch capture is driven by the recorder's own entrypoint. A full
recorder run **replaces** the committed baseline under
`tools/pulumi/recorder/fixtures/`, so redirect it:

```bash
cd tools/pulumi/recorder
make run FIXTURES_DIR=/tmp/rec/fixtures SNAPSHOTS_DIR=/tmp/rec/snapshots
```

then copy
`/tmp/rec/fixtures/by-command/35-preview-ci-escape-hatch/0006_POST_*_preview.json`
here. Re-recording the committed baseline is a separate maintenance action with
its own review — see `tools/pulumi/recorder/AGENTS.md`.
