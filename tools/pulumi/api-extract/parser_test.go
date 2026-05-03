package main

import "testing"

func TestExtractEndpointsExpandsUpdateKinds(t *testing.T) {
	input := []byte(`
addEndpoint("POST", "/api/stacks/{orgName}/{projectName}/{stackName}/{updateKind}/{updateID}", "startUpdate")
addEndpoint("GET", "/api/user", "getCurrentUser")
`)

	eps := extractEndpoints(input)
	wantCount := len(updateKinds) + 1
	if len(eps) != wantCount {
		t.Fatalf("expected %d endpoints, got %d", wantCount, len(eps))
	}

	wantNames := map[string]bool{
		"startUpdate_update":  false,
		"startUpdate_preview": false,
		"startUpdate_destroy": false,
		"startUpdate_refresh": false,
		"getCurrentUser":      false,
	}
	for _, e := range eps {
		if _, ok := wantNames[e.Name]; ok {
			wantNames[e.Name] = true
		}
	}
	for name, seen := range wantNames {
		if !seen {
			t.Fatalf("missing expected endpoint name %q", name)
		}
	}
}

func TestExtractAPIVersion(t *testing.T) {
	t.Run("const", func(t *testing.T) {
		got := extractAPIVersion([]byte(`const currentAPIVersion = 3`))
		if got != "application/vnd.pulumi+3" {
			t.Fatalf("unexpected version: %s", got)
		}
	})

	t.Run("literal", func(t *testing.T) {
		got := extractAPIVersion([]byte(`req.Header.Set("Accept", "application/vnd.pulumi+5")`))
		if got != "application/vnd.pulumi+5" {
			t.Fatalf("unexpected version: %s", got)
		}
	})

	t.Run("unknown", func(t *testing.T) {
		got := extractAPIVersion([]byte(`package main`))
		if got != "unknown" {
			t.Fatalf("unexpected version: %s", got)
		}
	})
}

func TestNormalizePath(t *testing.T) {
	got := normalizePath("/api/stacks/{orgName}/{projectName}/{stackName}/updates/{version}/contents/file/{path:.*}")
	want := "/api/stacks/{*}/{*}/{*}/updates/{*}/contents/file/{*}"
	if got != want {
		t.Fatalf("normalizePath mismatch\nwant: %s\ngot:  %s", want, got)
	}
}
