package main

import (
	"crypto/sha256"
	"fmt"
	"strings"
	"testing"
)

const version = "v3.242.0"

func deploymentJSON(resources string) string {
	magic := fmt.Sprintf("%x", sha256.Sum256([]byte(version)))
	return fmt.Sprintf(`{
  "manifest": {"time": "2026-07-12T00:00:00Z", "magic": "%s", "version": "%s"},
  "resources": [%s]
}`, magic, version, resources)
}

const stackResource = `{"urn": "urn:pulumi:dev::demo::pulumi:pulumi:Stack::demo-dev", "type": "pulumi:pulumi:Stack", "custom": false}`

func TestValidDeploymentPasses(t *testing.T) {
	message, code := check([]byte(deploymentJSON(stackResource)))
	if code != 0 {
		t.Fatalf("want exit 0, got %d: %s", code, message)
	}
}

func TestExportEnvelopeIsUnwrapped(t *testing.T) {
	envelope := fmt.Sprintf(`{"version": 3, "deployment": %s}`, deploymentJSON(stackResource))
	message, code := check([]byte(envelope))
	if code != 0 {
		t.Fatalf("want exit 0, got %d: %s", code, message)
	}
}

func TestDuplicateUrnFails(t *testing.T) {
	message, code := check([]byte(deploymentJSON(stackResource + "," + stackResource)))
	if code != 1 {
		t.Fatalf("want exit 1, got %d: %s", code, message)
	}
	if !strings.Contains(message, "duplicate resource") {
		t.Fatalf("want duplicate-resource rule in message, got: %s", message)
	}
}

func TestUnknownProviderReferenceFails(t *testing.T) {
	resource := `{"urn": "urn:pulumi:dev::demo::random:index/randomPet:RandomPet::pet", "type": "random:index/randomPet:RandomPet", "custom": true, "id": "pet-1", "provider": "urn:pulumi:dev::demo::pulumi:providers:random::default::missing"}`
	message, code := check([]byte(deploymentJSON(resource)))
	if code != 1 {
		t.Fatalf("want exit 1, got %d: %s", code, message)
	}
	if !strings.Contains(message, "unknown provider") {
		t.Fatalf("want unknown-provider rule in message, got: %s", message)
	}
}

func TestMagicMismatchFails(t *testing.T) {
	tampered := strings.Replace(deploymentJSON(stackResource), version+`"}`, "v0.0.0"+`"}`, 1)
	message, code := check([]byte(tampered))
	if code != 1 {
		t.Fatalf("want exit 1, got %d: %s", code, message)
	}
	if !strings.Contains(message, "magic") {
		t.Fatalf("want magic-cookie rule in message, got: %s", message)
	}
}

func TestVersion4EnvelopeIsUnwrapped(t *testing.T) {
	envelope := fmt.Sprintf(`{"version": 4, "features": ["views"], "deployment": %s}`, deploymentJSON(stackResource))
	message, code := check([]byte(envelope))
	if code != 0 {
		t.Fatalf("want exit 0, got %d: %s", code, message)
	}
}

func TestUnsupportedEnvelopeVersionIsRejected(t *testing.T) {
	_, code := check([]byte(`{"version": 5, "deployment": {}}`))
	if code != 2 {
		t.Fatalf("want exit 2, got %d", code)
	}
}

func TestGarbageInputIsRejected(t *testing.T) {
	_, code := check([]byte("not json"))
	if code != 2 {
		t.Fatalf("want exit 2, got %d", code)
	}
}
