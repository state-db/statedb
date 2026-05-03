package main

import (
	"crypto/sha256"
	"encoding/json"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

var camelSnakeRE = regexp.MustCompile(`([a-z])([A-Z])`)

const pulumiOpenAPIURL = "https://api.pulumi.com/api/openapi/pulumi-spec.json"

const pulumiOpenAPIRepoPath = "pkg/cmd/pulumi/cloud/testdata/openapi_public.json"

var (
	httpGetOpenAPI   = http.Get
	fetchFileAtRefFn = fetchFileAtRef
)

type openAPISource struct {
	Type           string
	Location       string
	SHA256         string
	FallbackReason string
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func parseOpenAPISpec(body []byte) (map[string]interface{}, error) {
	var spec map[string]interface{}
	if err := json.Unmarshal(body, &spec); err != nil {
		return nil, fmt.Errorf("parsing OpenAPI spec: %w", err)
	}
	return spec, nil
}

// fetchOpenAPISpec fetches a pinned spec fixture from pulumi/pulumi at ref when
// available, and falls back to Pulumi Cloud's live published spec.
func fetchOpenAPISpec(ref string) (map[string]interface{}, openAPISource, error) {
	repoURL := fmt.Sprintf("%s/%s/%s/%s/%s", rawGitHubBase, pulumiOwner, pulumiRepo, ref, pulumiOpenAPIRepoPath)
	fixtureBody, fixtureErr := fetchFileAtRefFn(pulumiOwner, pulumiRepo, pulumiOpenAPIRepoPath, ref)
	if fixtureErr == nil {
		spec, err := parseOpenAPISpec(fixtureBody)
		if err != nil {
			return nil, openAPISource{}, err
		}
		return spec, openAPISource{
			Type:     "repo-fixture",
			Location: repoURL,
			SHA256:   sha256Hex(fixtureBody),
		}, nil
	}

	resp, err := httpGetOpenAPI(pulumiOpenAPIURL) //nolint:gosec
	if err != nil {
		return nil, openAPISource{}, fmt.Errorf("fetching OpenAPI spec fallback after %s failed: %w", repoURL, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, openAPISource{}, fmt.Errorf("HTTP %d fetching OpenAPI spec fallback after %s failed", resp.StatusCode, repoURL)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, openAPISource{}, err
	}
	spec, err := parseOpenAPISpec(body)
	if err != nil {
		return nil, openAPISource{}, err
	}

	return spec, openAPISource{
		Type:           "live-fallback",
		Location:       pulumiOpenAPIURL,
		SHA256:         sha256Hex(body),
		FallbackReason: fixtureErr.Error(),
	}, nil
}

// filterSpec returns a new OpenAPI spec containing only paths that match the
// provided endpoints, plus any components/schemas they reference.
func filterSpec(spec map[string]interface{}, endpoints []Endpoint) map[string]interface{} {
	// Build a lookup: normalized_method_path → endpoint
	lookup := make(map[string]Endpoint, len(endpoints))
	for _, e := range endpoints {
		lookup[e.Method+" "+normalizePath(e.Path)] = e
	}

	// Walk the spec paths and keep matching ones.
	allPaths, _ := spec["paths"].(map[string]interface{})
	matchedPaths := make(map[string]interface{})
	for specPath, pathItem := range allPaths {
		normalizedSpecPath := normalizePath(specPath)
		methods, ok := pathItem.(map[string]interface{})
		if !ok {
			continue
		}
		filteredItem := make(map[string]interface{})
		for method, opDef := range methods {
			upperMethod := strings.ToUpper(method)
			key := upperMethod + " " + normalizedSpecPath
			if _, found := lookup[key]; found {
				filteredItem[method] = opDef
			}
		}
		if len(filteredItem) > 0 {
			matchedPaths[specPath] = filteredItem
		}
	}

	// Collect all $ref schema names used in matched paths.
	usedSchemas := make(map[string]bool)
	collectRefs(matchedPaths, usedSchemas)

	// Transitively resolve refs within the collected schemas.
	allSchemas, _ := spec["components"].(map[string]interface{})
	if allSchemas != nil {
		allSchemas, _ = allSchemas["schemas"].(map[string]interface{})
	}
	if allSchemas != nil {
		resolveTransitiveRefs(allSchemas, usedSchemas)
	}

	// Build filtered components section.
	filteredComponents := make(map[string]interface{})
	if allSchemas != nil && len(usedSchemas) > 0 {
		filteredSchemas := make(map[string]interface{})
		for name := range usedSchemas {
			if schema, ok := allSchemas[name]; ok {
				filteredSchemas[name] = schema
			}
		}
		filteredComponents["schemas"] = filteredSchemas
	}

	// Copy top-level spec metadata, replace paths and components.
	result := make(map[string]interface{})
	for k, v := range spec {
		switch k {
		case "paths":
			result["paths"] = matchedPaths
		case "components":
			result["components"] = filteredComponents
		default:
			result[k] = v
		}
	}
	return result
}

// collectRefs recursively walks v and adds any "#/components/schemas/X" ref names
// to refs.
func collectRefs(v interface{}, refs map[string]bool) {
	switch t := v.(type) {
	case map[string]interface{}:
		for k, val := range t {
			if k == "$ref" {
				if s, ok := val.(string); ok {
					if name, ok := schemaRefName(s); ok {
						refs[name] = true
					}
				}
			}
			collectRefs(val, refs)
		}
	case []interface{}:
		for _, item := range t {
			collectRefs(item, refs)
		}
	}
}

// resolveTransitiveRefs keeps resolving refs in allSchemas until no new names
// are added to the seen set.
func resolveTransitiveRefs(allSchemas map[string]interface{}, seen map[string]bool) {
	for {
		prev := len(seen)
		for name := range seen {
			if schema, ok := allSchemas[name]; ok {
				collectRefs(schema, seen)
			}
		}
		if len(seen) == prev {
			break
		}
	}
}

func schemaRefName(ref string) (string, bool) {
	const prefix = "#/components/schemas/"
	if strings.HasPrefix(ref, prefix) {
		return strings.TrimPrefix(ref, prefix), true
	}
	return "", false
}

// enrichEndpoints overwrites each endpoint's Category with the last tag from its
// matching OpenAPI operation, which is authoritative and version-independent.
// Endpoints not present in the spec keep the path-heuristic category from categoryFor.
func enrichEndpoints(spec map[string]interface{}, endpoints []Endpoint) []Endpoint {
	// Build lookup: normalized "METHOD path" → tags slice
	type tagEntry struct{ tags []string }
	byKey := make(map[string]tagEntry)

	paths, _ := spec["paths"].(map[string]interface{})
	for specPath, pathItem := range paths {
		normPath := normalizePath(specPath)
		methods, _ := pathItem.(map[string]interface{})
		for method, opDef := range methods {
			opMap, _ := opDef.(map[string]interface{})
			tagsRaw, _ := opMap["tags"].([]interface{})
			var tags []string
			for _, t := range tagsRaw {
				if s, ok := t.(string); ok {
					tags = append(tags, s)
				}
			}
			key := strings.ToUpper(method) + " " + normPath
			byKey[key] = tagEntry{tags}
		}
	}

	result := make([]Endpoint, len(endpoints))
	for i, e := range endpoints {
		key := e.Method + " " + normalizePath(e.Path)
		if entry, found := byKey[key]; found && len(entry.tags) > 0 {
			// Last tag is the most specific (e.g. ["Stacks","StackUpdates"] → "stack_updates").
			e.Category = toSnakeCase(entry.tags[len(entry.tags)-1])
		}
		result[i] = e
	}
	return result
}

// toSnakeCase converts a CamelCase or space-separated string to snake_case.
// "StackUpdates" → "stack_updates", "AI Agents" → "ai_agents".
func toSnakeCase(s string) string {
	s = camelSnakeRE.ReplaceAllString(s, "${1}_${2}")
	s = strings.ReplaceAll(s, " ", "_")
	return strings.ToLower(s)
}

// marshalYAML marshals the spec to YAML bytes.
func marshalYAML(spec map[string]interface{}) ([]byte, error) {
	return yaml.Marshal(spec)
}

// specPathCount returns the number of paths in a filtered spec.
func specPathCount(spec map[string]interface{}) int {
	paths, _ := spec["paths"].(map[string]interface{})
	return len(paths)
}
