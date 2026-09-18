# Pulumi diff mechanism

Research date: 2026-09-18

## Question

How does the open-source Pulumi engine compute, transport, and render resource
diffs, especially when an old resource has provider-only output properties that
do not occur in the new desired inputs?

This trace uses Pulumi CLI v3.259.0, the version currently cached for state-db
compatibility work.

## Answer

Pulumi does not define every preview or update diff as `old.outputs` versus
`new.inputs`.

The engine first asks the provider to validate and normalize the program's
desired inputs with `Check`. It then calls the provider's `Diff` method with
three distinct property bags:

- the resource's previous checked inputs;
- the resource's previous complete output state; and
- the newly checked desired inputs.

The provider decides whether there is a change and may return either coarse
top-level `diffs` or a path-level `detailedDiff`. For each detailed path,
`inputDiff` tells the renderer which old value is relevant. In an ordinary
preview or update, `inputDiff: true` means old input to new input, while
`inputDiff: false` means old output/state to new input. When no detailed diff is
present, Pulumi's preview/update renderer falls back to old inputs versus new
inputs and uses the coarse `diffs` as its top-level inclusion filter.

Consequently, an output-only property must not appear as deleted merely because
it exists in `old.outputs` but not `new.inputs`. That comparison is valid only
when a provider explicitly supplies a detailed path with `inputDiff: false`.

## End-to-end trace

### 1. `Check` produces the desired input state

For an existing resource, the step generator sends the previous resource inputs
as `Check.olds` and the preprocessed program properties as `Check.news`. The
provider's returned properties replace `new.Inputs`; these checked properties,
which may include provider defaults, become the desired inputs used by later
lifecycle calls
([step generator](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step_generator.go#L1264-L1351),
[provider RPC adapter](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider_plugin.go#L1167-L1280)).

The protocol says the same thing directly: `CheckRequest.olds` and `.news` are
old and new inputs, and a valid `CheckResponse.inputs` may contain defaults that
must be persisted and passed to subsequent lifecycle methods
([provider protocol](https://github.com/pulumi/pulumi/blob/v3.259.0/proto/pulumi/provider.proto#L588-L663)).

### 2. `Diff` receives three property bags

After `Check`, the engine calls `Diff` with `OldInputs`, `OldOutputs`, and
`NewInputs`. The internal request type names all three explicitly, and
`diffResource` populates them from the old state and the newly checked inputs
([provider interface](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider.go#L253-L280),
[engine call site](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step_generator.go#L2961-L2995)).

The RPC adapter maps these values as follows:

| Engine value | Provider RPC field | Meaning |
| --- | --- | --- |
| `OldInputs` | `old_inputs` | Previous checked inputs |
| `OldOutputs` | `olds` | Previous provider output/state |
| `NewInputs` | `news` | Newly checked desired inputs |

This mapping is implemented in the provider RPC adapter
([RPC call](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider_plugin.go#L1318-L1366))
and documented in the protocol
([`DiffRequest`](https://github.com/pulumi/pulumi/blob/v3.259.0/proto/pulumi/provider.proto#L679-L718)).

The provider therefore has enough information to implement either input-aware
or state-aware semantics. The engine does not infer that every provider diff is
one or the other.

### 3. The provider returns coarse and/or detailed diff information

A `DiffResponse` contains:

- `changes`, the high-level none/some/unknown result;
- `diffs`, top-level changed property names;
- `replaces`, top-level replacement reasons;
- `detailedDiff`, property paths mapped to add, delete, update, or their
  replacement variants; and
- `hasDetailedDiff`, which distinguishes an absent detailed diff from a present
  but empty one.

The protocol requires consistency between `diffs` and `detailedDiff` and states
that `diffs` supports top-level names only, whereas detailed diff keys are full
property paths
([`DiffResponse`](https://github.com/pulumi/pulumi/blob/v3.259.0/proto/pulumi/provider.proto#L754-L815)).

Each detailed `PropertyDiff` also has an `inputDiff` bit. The protocol defines
it as the distinction between old/new inputs and old/new states
([`PropertyDiff`](https://github.com/pulumi/pulumi/blob/v3.259.0/proto/pulumi/provider.proto#L721-L750)).
Pulumi's in-process type phrases the normal-update interpretation more
concretely: it is true for old input versus new input, rather than old state
versus new input
([provider type](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider.go#L729-L750)).

The RPC decoder returns `nil` when `hasDetailedDiff` is false. When the bit is
true, it returns a map even if that map has zero entries, preserving the
provider's signal
([decoder](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider_plugin.go#L757-L788)).

If a legacy provider returns `DIFF_UNKNOWN`, the engine performs its own
old-input/new-input comparison, sets coarse changed keys, and synthesizes a
detailed diff with `inputDiff: true`
([fallback](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step_generator.go#L2968-L2994)).
For an ordinary `DIFF_SOME` response with no detailed diff, the engine retains
the coarse response; display code supplies the visual fallback.

### 4. Diff metadata passes through steps and events unchanged

The step generator installs the provider's `ChangedKeys` and `DetailedDiff` on
update and replacement steps
([step construction](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step_generator.go#L2148-L2175)).
The step event builder copies `Diffs` and `DetailedDiff` into
`StepEventMetadata`, together with the complete old and new resource states
([event metadata](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/engine/events.go#L341-L355),
[event construction](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/engine/events.go#L512-L544)).

The service-facing JSON event conversion preserves each detailed path's kind
and `inputDiff`
([event conversion](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/events.go#L273-L325)).
The API type deliberately does not use `omitempty` for `detailedDiff`, because
`null` and `{}` have different meanings
([event API contract](https://github.com/pulumi/pulumi/blob/v3.259.0/sdk/go/common/apitype/events.go#L214-L243)).

This means state-db receives enough information in uploaded engine events to
reproduce Pulumi's rendering decision. It does not need to invent provider diff
semantics.

## CLI rendering rules

### Detailed diffs

When `DetailedDiff` is non-nil, the diff renderer calls
`TranslateDetailedDiff`. For every path, that function selects values as
follows
([translator](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/engine/detailedDiff.go#L159-L214)):

| Operation context | `inputDiff` | Old value | New value |
| --- | --- | --- | --- |
| Preview/update | `true` | `old.inputs` | `new.inputs` |
| Preview/update | `false` | `old.outputs` | `new.inputs` |
| Refresh | `true` | `old.inputs` | `new.inputs` |
| Refresh | `false` | `old.outputs` | `new.outputs` |

The provider's `diffKind` is authoritative. `TranslateDetailedDiff` passes that
kind to `addDiff`, which constructs the requested add, delete, update, or
replacement-shaped value diff; it does not discard the kind and infer a new one
only from value presence
([translation mechanics](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/engine/detailedDiff.go#L26-L153)).

A present but empty detailed map follows this detailed-diff branch. The
translator produces no property changes; Pulumi does not fall back to the
coarse keys. The event conversion has a regression test specifically ensuring
that `{}` survives serialization
([empty-map test](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/events_test.go#L52-L70)).

### No detailed diff during preview/update

When `DetailedDiff` is nil, the normal diff renderer uses its generic resource
property renderer
([branch](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/diff.go#L432-L460)).
For a resource pre-event, the proposed state's outputs are nil by construction
([new state](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step_generator.go#L789-L835)).
The renderer therefore compares `old.inputs` to `new.inputs` and passes coarse
`diffs` as the top-level inclusion list
([fallback selection](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/object_diff.go#L208-L244),
[diff computation](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/object_diff.go#L777-L817)).

The interactive progress view has the same normal fallback: if there is no
detailed diff, it compares old and new inputs
([progress row](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/rows.go#L466-L478)).

This is why a coarse `diffs: ["settings"]` entry does not authorize comparing
the whole old output object at `settings` to the new input object. It selects the
top-level key from an input-to-input object diff.

### Refresh

Refresh first calls `Read` with the stored inputs and outputs. It accepts any
refreshed inputs and outputs returned by the provider
([refresh read](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step.go#L1559-L1645)).

For a managed resource, Pulumi then asks what an immediate no-program-change
update would do by calling `Diff` in the reverse direction: refreshed inputs
and outputs are supplied as the old state, and the pre-refresh inputs are
supplied as desired inputs. It inverts the returned diff before attaching it to
the refresh step
([refresh diff](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/deploy/step.go#L1679-L1716),
[diff inversion](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/resource/plugin/provider.go#L833-L854)).

Refresh diffs are rendered from the resource outputs event with `refresh=true`
([refresh rendering](https://github.com/pulumi/pulumi/blob/v3.259.0/pkg/backend/display/diff.go#L484-L513)).
That flag makes non-input detailed paths compare old outputs to refreshed new
outputs. With no detailed diff, the generic renderer sees complete new outputs
and compares old outputs to new outputs.

## Implications for state-db

The current state-db UI starts from the right property-path set but selects the
wrong old bag when the event has no detailed diff. In
[`propertyChanges`](../../modules/web/src/routes/run-detail.tsx), every entry
defaults to `oldOutputs` unless its detailed record says `inputDiff: true`.
Coarse entries have no detailed record, so they incorrectly receive the
old-output/new-input treatment.

The compatible behavior is:

1. Preserve the distinction between `detailedDiff: null` and
   `detailedDiff: {}`.
2. When detailed diff is absent for an ordinary preview/update, compute values
   from `old.inputs` and `new.inputs`, using `diffs` only as a top-level filter.
3. When detailed diff is present, use each path's `inputDiff` to select
   `old.inputs` or `old.outputs`; for refresh, select `new.outputs` for
   non-input paths.
4. Preserve and render the supplied `diffKind` instead of re-inferring the kind
   from the chosen values.
5. Treat refresh as a separate rendering context. Its direction and value
   sources are intentionally different from preview/update.

Applied to the reported case, an old provider-only output field that is absent
from both old and new inputs is invisible in Pulumi's coarse preview/update
fallback. It appears only if the provider explicitly identifies that path in a
detailed state-aware diff.
