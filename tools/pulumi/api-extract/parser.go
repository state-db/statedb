package main

import (
	"regexp"
	"strings"
)

var (
	addEndpointRE    = regexp.MustCompile(`addEndpoint\("([A-Z]+)",\s*"([^"]+)",\s*"([^"]+)"\)`)
	apiVersionConstRE = regexp.MustCompile(`const\s+currentAPIVersion\s*=\s*(\d+)`)
	apiVersionLitRE   = regexp.MustCompile(`"application/vnd\.pulumi\+(\d+)"`)
	pathParamRE       = regexp.MustCompile(`\{[^}]+\}`)
)

// updateKinds are the concrete values that replace {updateKind} in path templates.
var updateKinds = []string{"update", "preview", "destroy", "refresh"}

// Endpoint is a single HTTP endpoint extracted from the Pulumi CLI source.
type Endpoint struct {
	Method   string `json:"method"`
	Path     string `json:"path"`
	Name     string `json:"name"`
	Category string `json:"category"`
}

// Key returns a stable identifier for deduplication and comparison.
func (e Endpoint) Key() string {
	return e.Method + " " + e.Path
}

// extractEndpoints parses api_endpoints.go and returns all defined endpoints,
// expanding the {updateKind} placeholder to its concrete values.
func extractEndpoints(apiEndpointsGo []byte) []Endpoint {
	matches := addEndpointRE.FindAllSubmatch(apiEndpointsGo, -1)
	var endpoints []Endpoint
	for _, m := range matches {
		method := string(m[1])
		rawPath := string(m[2])
		name := string(m[3])

		if strings.Contains(rawPath, "{updateKind}") {
			for _, kind := range updateKinds {
				expanded := strings.ReplaceAll(rawPath, "{updateKind}", kind)
				endpoints = append(endpoints, Endpoint{
					Method:   method,
					Path:     expanded,
					Name:     name + "_" + kind,
					Category: categoryFor(expanded),
				})
			}
		} else {
			endpoints = append(endpoints, Endpoint{
				Method:   method,
				Path:     rawPath,
				Name:     name,
				Category: categoryFor(rawPath),
			})
		}
	}
	return endpoints
}

// extractAPIVersion reads the API version from api.go (either the currentAPIVersion
// constant or a hardcoded string literal) and returns the Accept header value.
func extractAPIVersion(apiGo []byte) string {
	if m := apiVersionConstRE.FindSubmatch(apiGo); m != nil {
		return "application/vnd.pulumi+" + string(m[1])
	}
	if m := apiVersionLitRE.FindSubmatch(apiGo); m != nil {
		return "application/vnd.pulumi+" + string(m[1])
	}
	return "unknown"
}

// normalizePath replaces all {param} placeholders with {*} for path matching.
func normalizePath(p string) string {
	return pathParamRE.ReplaceAllString(p, "{*}")
}

func categoryFor(p string) string {
	switch {
	case strings.Contains(p, "/checkpoint") || strings.Contains(p, "/checkpointdelta") || strings.Contains(p, "/checkpointverbatim"):
		return "checkpoint"
	case strings.Contains(p, "/renew_lease"):
		return "locking"
	case strings.Contains(p, "/events"):
		return "events"
	case strings.Contains(p, "/complete") || strings.Contains(p, "/cancel"):
		return "update_lifecycle"
	case strings.Contains(p, "/update/") || strings.Contains(p, "/preview/") ||
		strings.Contains(p, "/destroy/") || strings.Contains(p, "/refresh/"):
		return "update_lifecycle"
	case strings.Contains(p, "/update") || strings.Contains(p, "/preview") ||
		strings.Contains(p, "/destroy"):
		return "update_create"
	case strings.Contains(p, "/export") || strings.Contains(p, "/import"):
		return "state_io"
	case strings.Contains(p, "/encrypt") || strings.Contains(p, "/decrypt"):
		return "secrets"
	case strings.Contains(p, "/deployments"):
		return "deployments"
	case strings.Contains(p, "/policypacks") || strings.Contains(p, "/policygroups"):
		return "policy"
	case strings.Contains(p, "/registry/"):
		return "registry"
	case strings.Contains(p, "/search/"):
		return "search"
	case strings.Contains(p, "/ai") || strings.Contains(p, "/neo"):
		return "ai"
	case strings.Contains(p, "/api/stacks"):
		return "stacks"
	case strings.Contains(p, "/api/user") || strings.Contains(p, "/api/orgs"):
		return "account"
	default:
		return "other"
	}
}
