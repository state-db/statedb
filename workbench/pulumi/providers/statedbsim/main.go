package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/rand/v2"
	"os"
	"path/filepath"
	"strings"
	"time"

	p "github.com/pulumi/pulumi-go-provider"
	"github.com/pulumi/pulumi-go-provider/infer"
)

const (
	packageName = "statedbsim"
	version     = "0.1.0"
)

func main() {
	provider, err := infer.NewProviderBuilder().
		WithResources(infer.Resource(SimulatedResource{})).
		WithNamespace("statedb").
		WithDisplayName("state-db simulator").
		WithDescription("Local-only Pulumi provider for generating large, slow, and failure-prone synthetic resource graphs.").
		Build()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := provider.Run(context.Background(), packageName, version); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

type SimulatedResource struct{}

type SimulatedResourceArgs struct {
	Sequence         int    `pulumi:"sequence"`
	Level            int    `pulumi:"level"`
	Revision         int    `pulumi:"revision"`
	ReplacementKey   string `pulumi:"replacementKey,optional"`
	PayloadBytes     int    `pulumi:"payloadBytes,optional"`
	DiffDelayMs      int    `pulumi:"diffDelayMs,optional"`
	CreateDelayMs    int    `pulumi:"createDelayMs,optional"`
	CreateJitterMs   int    `pulumi:"createJitterMs,optional"`
	UpdateDelayMs    int    `pulumi:"updateDelayMs,optional"`
	DeleteDelayMs    int    `pulumi:"deleteDelayMs,optional"`
	FailureMode      string `pulumi:"failureMode,optional"`
	FailureOperation string `pulumi:"failureOperation,optional"`
	FailureModulo    int    `pulumi:"failureModulo,optional"`
	FailureSeed      string `pulumi:"failureSeed,optional"`
	FailOnceKey      string `pulumi:"failOnceKey,optional"`
}

type SimulatedResourceState struct {
	SimulatedResourceArgs
	Name         string `pulumi:"name"`
	Digest       string `pulumi:"digest"`
	Payload      string `pulumi:"payload"`
	CreatedAt    string `pulumi:"createdAt"`
	UpdatedAt    string `pulumi:"updatedAt"`
	Operation    string `pulumi:"operation"`
	OperationRun int    `pulumi:"operationRun"`
}

func (SimulatedResource) Create(
	ctx context.Context,
	req infer.CreateRequest[SimulatedResourceArgs],
) (infer.CreateResponse[SimulatedResourceState], error) {
	state := newState(req.Name, req.Inputs, "create", 1, "")
	id := resourceID(req.Name, req.Inputs.ReplacementKey)
	if req.DryRun {
		return infer.CreateResponse[SimulatedResourceState]{ID: id, Output: state}, nil
	}
	if err := sleep(ctx, createDelay(req.Inputs)); err != nil {
		return infer.CreateResponse[SimulatedResourceState]{}, err
	}
	if err := maybeFail(req.Name, "create", req.Inputs); err != nil {
		return infer.CreateResponse[SimulatedResourceState]{}, err
	}
	logOp("create", req.Name, req.Inputs)
	return infer.CreateResponse[SimulatedResourceState]{ID: id, Output: state}, nil
}

func (SimulatedResource) Diff(
	ctx context.Context,
	req infer.DiffRequest[SimulatedResourceArgs, SimulatedResourceState],
) (infer.DiffResponse, error) {
	if err := sleep(ctx, req.Inputs.DiffDelayMs); err != nil {
		return infer.DiffResponse{}, err
	}
	in, st := req.Inputs, req.State
	properties := []struct {
		name    string
		changed bool
		kind    p.DiffKind
	}{
		{"replacementKey", in.ReplacementKey != st.ReplacementKey, p.UpdateReplace},
		{"revision", in.Revision != st.Revision, p.Update},
		{"payloadBytes", in.PayloadBytes != st.PayloadBytes, p.Update},
		{"diffDelayMs", in.DiffDelayMs != st.DiffDelayMs, p.Update},
		{"createDelayMs", in.CreateDelayMs != st.CreateDelayMs, p.Update},
		{"createJitterMs", in.CreateJitterMs != st.CreateJitterMs, p.Update},
		{"updateDelayMs", in.UpdateDelayMs != st.UpdateDelayMs, p.Update},
		{"deleteDelayMs", in.DeleteDelayMs != st.DeleteDelayMs, p.Update},
		{"failureMode", in.FailureMode != st.FailureMode, p.Update},
		{"failureOperation", in.FailureOperation != st.FailureOperation, p.Update},
		{"failureModulo", in.FailureModulo != st.FailureModulo, p.Update},
		{"failureSeed", in.FailureSeed != st.FailureSeed, p.Update},
		{"failOnceKey", in.FailOnceKey != st.FailOnceKey, p.Update},
	}
	diff := map[string]p.PropertyDiff{}
	for _, prop := range properties {
		if prop.changed {
			diff[prop.name] = p.PropertyDiff{Kind: prop.kind}
		}
	}
	return infer.DiffResponse{
		DeleteBeforeReplace: true,
		HasChanges:          len(diff) > 0,
		DetailedDiff:        diff,
	}, nil
}

func (SimulatedResource) Update(
	ctx context.Context,
	req infer.UpdateRequest[SimulatedResourceArgs, SimulatedResourceState],
) (infer.UpdateResponse[SimulatedResourceState], error) {
	state := newState(req.State.Name, req.Inputs, "update", req.State.OperationRun+1, req.State.CreatedAt)
	if req.DryRun {
		return infer.UpdateResponse[SimulatedResourceState]{Output: state}, nil
	}
	if err := sleep(ctx, req.Inputs.UpdateDelayMs); err != nil {
		return infer.UpdateResponse[SimulatedResourceState]{}, err
	}
	if err := maybeFail(req.State.Name, "update", req.Inputs); err != nil {
		return infer.UpdateResponse[SimulatedResourceState]{}, err
	}
	logOp("update", req.State.Name, req.Inputs)
	return infer.UpdateResponse[SimulatedResourceState]{Output: state}, nil
}

func (SimulatedResource) Delete(
	ctx context.Context,
	req infer.DeleteRequest[SimulatedResourceState],
) (infer.DeleteResponse, error) {
	if err := sleep(ctx, req.State.DeleteDelayMs); err != nil {
		return infer.DeleteResponse{}, err
	}
	if err := maybeFail(req.State.Name, "delete", req.State.SimulatedResourceArgs); err != nil {
		return infer.DeleteResponse{}, err
	}
	logOp("delete", req.State.Name, req.State.SimulatedResourceArgs)
	return infer.DeleteResponse{}, nil
}

func (SimulatedResource) Read(
	_ context.Context,
	req infer.ReadRequest[SimulatedResourceArgs, SimulatedResourceState],
) (infer.ReadResponse[SimulatedResourceArgs, SimulatedResourceState], error) {
	return infer.ReadResponse[SimulatedResourceArgs, SimulatedResourceState]{
		ID:     req.ID,
		Inputs: req.Inputs,
		State:  req.State,
	}, nil
}

func (*SimulatedResource) Annotate(a infer.Annotator) {
	a.Describe(&SimulatedResource{}, "A synthetic local resource with configurable delays, payload size, replacements, and failures.")
}

func newState(name string, inputs SimulatedResourceArgs, operation string, operationRun int, createdAt string) SimulatedResourceState {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if createdAt == "" {
		createdAt = now
	}
	payload := buildPayload(inputs.PayloadBytes, name, inputs.Revision)
	digest := sha256.Sum256([]byte(fmt.Sprintf("%s|%d|%s|%s", name, inputs.Revision, inputs.ReplacementKey, payload)))
	return SimulatedResourceState{
		SimulatedResourceArgs: inputs,
		Name:                  name,
		Digest:                hex.EncodeToString(digest[:]),
		Payload:               payload,
		CreatedAt:             createdAt,
		UpdatedAt:             now,
		Operation:             operation,
		OperationRun:          operationRun,
	}
}

func resourceID(name, replacementKey string) string {
	sum := sha256.Sum256([]byte(name + "|" + replacementKey))
	return "sim-" + hex.EncodeToString(sum[:])[:24]
}

// createDelay is the base create delay plus a random jitter, so a batch of
// resources finishes provisioning at staggered, unpredictable times.
func createDelay(inputs SimulatedResourceArgs) int {
	delay := inputs.CreateDelayMs
	if inputs.CreateJitterMs > 0 {
		delay += rand.IntN(inputs.CreateJitterMs)
	}
	return delay
}

func logOp(operation, name string, args SimulatedResourceArgs) {
	fmt.Fprintf(os.Stderr, "statedbsim %s name=%s sequence=%d level=%d revision=%d\n",
		operation, name, args.Sequence, args.Level, args.Revision)
}

func buildPayload(size int, name string, revision int) string {
	if size <= 0 {
		return ""
	}
	unit := fmt.Sprintf("%s:%d:", name, revision)
	var b strings.Builder
	b.Grow(size)
	for b.Len() < size {
		b.WriteString(unit)
	}
	return b.String()[:size]
}

func sleep(ctx context.Context, delayMs int) error {
	if delayMs <= 0 {
		return nil
	}
	timer := time.NewTimer(time.Duration(delayMs) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func maybeFail(name, operation string, inputs SimulatedResourceArgs) error {
	mode := strings.ToLower(strings.TrimSpace(inputs.FailureMode))
	if mode == "" || mode == "none" {
		return nil
	}
	if !operationMatches(operation, inputs.FailureOperation) {
		return nil
	}

	switch mode {
	case "always":
		return fmt.Errorf("statedbsim forced %s failure for %s", operation, name)
	case "hash":
		modulo := inputs.FailureModulo
		if modulo <= 0 {
			modulo = 100
		}
		sum := sha256.Sum256([]byte(inputs.FailureSeed + "|" + operation + "|" + name))
		if int(sum[0])%modulo == 0 {
			return fmt.Errorf("statedbsim hash-selected %s failure for %s", operation, name)
		}
	case "once":
		key := inputs.FailOnceKey
		if key == "" {
			key = inputs.FailureSeed + "|" + operation + "|" + name
		}
		marker := filepath.Join(os.TempDir(), "statedbsim", markerName(key))
		if _, err := os.Stat(marker); err == nil {
			return nil
		}
		if err := os.MkdirAll(filepath.Dir(marker), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(marker, []byte(time.Now().UTC().Format(time.RFC3339Nano)), 0o644); err != nil {
			return err
		}
		return fmt.Errorf("statedbsim one-shot %s failure for %s", operation, name)
	default:
		return fmt.Errorf("unsupported statedbsim failureMode %q", inputs.FailureMode)
	}
	return nil
}

func operationMatches(operation, configured string) bool {
	configured = strings.ToLower(strings.TrimSpace(configured))
	return configured == "" || configured == "any" || configured == operation
}

func markerName(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:])
}
