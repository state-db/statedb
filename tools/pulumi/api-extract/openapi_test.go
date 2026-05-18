package main

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFilterSpecKeepsMatchedPathsAndSchemas(t *testing.T) {
	spec := map[string]interface{}{
		"paths": map[string]interface{}{
			"/api/stacks/{orgName}": map[string]interface{}{
				"get": map[string]interface{}{
					"responses": map[string]interface{}{
						"200": map[string]interface{}{
							"content": map[string]interface{}{
								"application/json": map[string]interface{}{
									"schema": map[string]interface{}{"$ref": "#/components/schemas/Resp"},
								},
							},
						},
					},
				},
			},
			"/api/other": map[string]interface{}{
				"post": map[string]interface{}{
					"responses": map[string]interface{}{
						"200": map[string]interface{}{
							"content": map[string]interface{}{
								"application/json": map[string]interface{}{
									"schema": map[string]interface{}{"$ref": "#/components/schemas/Unused"},
								},
							},
						},
					},
				},
			},
		},
		"components": map[string]interface{}{
			"schemas": map[string]interface{}{
				"Resp": map[string]interface{}{
					"type": "object",
					"properties": map[string]interface{}{
						"child": map[string]interface{}{"$ref": "#/components/schemas/Child"},
					},
				},
				"Child":  map[string]interface{}{"type": "string"},
				"Unused": map[string]interface{}{"type": "number"},
			},
		},
	}

	eps := []Endpoint{{Method: "GET", Path: "/api/stacks/{x}"}}
	filtered := filterSpec(spec, eps)

	paths := filtered["paths"].(map[string]interface{})
	if len(paths) != 1 {
		t.Fatalf("expected 1 path, got %d", len(paths))
	}
	if _, ok := paths["/api/stacks/{orgName}"]; !ok {
		t.Fatalf("expected matched path to be present")
	}

	components := filtered["components"].(map[string]interface{})
	schemas := components["schemas"].(map[string]interface{})
	if _, ok := schemas["Resp"]; !ok {
		t.Fatalf("expected Resp schema")
	}
	if _, ok := schemas["Child"]; !ok {
		t.Fatalf("expected transitive Child schema")
	}
	if _, ok := schemas["Unused"]; ok {
		t.Fatalf("did not expect Unused schema")
	}
}

func TestFetchOpenAPISpecPrefersRepoFixture(t *testing.T) {
	oldFetch := fetchFileAtRefFn
	oldGet := httpGetOpenAPI
	t.Cleanup(func() {
		fetchFileAtRefFn = oldFetch
		httpGetOpenAPI = oldGet
	})

	fetchFileAtRefFn = func(owner, repo, filePath, ref string) ([]byte, error) {
		return []byte(`{"paths":{},"components":{"schemas":{}}}`), nil
	}
	httpGetOpenAPI = func(string) (*http.Response, error) {
		t.Fatal("live fetch should not be called when fixture exists")
		return nil, nil
	}

	_, source, err := fetchOpenAPISpec("vX")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if source.Type != "repo-fixture" {
		t.Fatalf("expected repo-fixture, got %s", source.Type)
	}
	if source.SHA256 == "" {
		t.Fatalf("expected source hash")
	}
}

func TestFetchOpenAPISpecFallsBackToLive(t *testing.T) {
	oldFetch := fetchFileAtRefFn
	oldGet := httpGetOpenAPI
	t.Cleanup(func() {
		fetchFileAtRefFn = oldFetch
		httpGetOpenAPI = oldGet
	})

	fetchFileAtRefFn = func(owner, repo, filePath, ref string) ([]byte, error) {
		return nil, errors.New("not found")
	}
	httpGetOpenAPI = func(string) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader(`{"paths":{},"components":{"schemas":{}}}`)),
		}, nil
	}

	_, source, err := fetchOpenAPISpec("vOld")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if source.Type != "live-fallback" {
		t.Fatalf("expected live-fallback, got %s", source.Type)
	}
	if source.Location != pulumiOpenAPIURL {
		t.Fatalf("unexpected live location: %s", source.Location)
	}
}

func TestGetOpenAPIUsesDescriptiveUserAgent(t *testing.T) {
	var gotUserAgent string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUserAgent = r.UserAgent()
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(server.Close)

	resp, err := getOpenAPI(server.URL)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	_ = resp.Body.Close()

	if gotUserAgent != apiExtractUserAgent {
		t.Fatalf("unexpected user agent: %q", gotUserAgent)
	}
}

func TestToSnakeCase(t *testing.T) {
	if got := toSnakeCase("StackUpdates"); got != "stack_updates" {
		t.Fatalf("unexpected snake case: %s", got)
	}
	if got := toSnakeCase("AI Agents"); got != "ai_agents" {
		t.Fatalf("unexpected snake case: %s", got)
	}
}
