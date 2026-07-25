package main

import (
	"strings"
	"testing"
)

func TestPayloadHasRequestedSize(t *testing.T) {
	got := buildPayload(37, "node-0001", 2)
	if len(got) != 37 {
		t.Fatalf("payload length = %d, want 37", len(got))
	}
	if !strings.HasPrefix(got, "node-0001:2:") {
		t.Fatalf("payload prefix = %q", got)
	}
}

func TestCreateDelayStaysWithinJitter(t *testing.T) {
	fixed := createDelay(SimulatedResourceArgs{CreateDelayMs: 40})
	if fixed != 40 {
		t.Fatalf("create delay without jitter = %d, want 40", fixed)
	}
	for i := 0; i < 1000; i++ {
		got := createDelay(SimulatedResourceArgs{CreateDelayMs: 40, CreateJitterMs: 10})
		if got < 40 || got >= 50 {
			t.Fatalf("create delay with jitter = %d, want [40, 50)", got)
		}
	}
}

func TestHashFailureIsDeterministic(t *testing.T) {
	inputs := SimulatedResourceArgs{
		FailureMode:      "hash",
		FailureOperation: "create",
		FailureModulo:    1,
		FailureSeed:      "seed",
	}
	if err := maybeFail("node-0001", "update", inputs); err != nil {
		t.Fatalf("unexpected non-matching operation failure: %v", err)
	}
	if err := maybeFail("node-0001", "create", inputs); err == nil {
		t.Fatal("expected hash failure with modulo 1")
	}
}

func TestResourceIDChangesWithReplacementKey(t *testing.T) {
	a := resourceID("node-0001", "a")
	b := resourceID("node-0001", "b")
	if a == b {
		t.Fatal("resource IDs should differ when replacement key differs")
	}
}
