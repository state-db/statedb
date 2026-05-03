package main

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// Scope defines which endpoints to include in the openapi-subset output.
// Both include and exclude support exact names and '*'-suffix prefix globs.
// If include is non-empty, only matching endpoints are kept.
// Exclude patterns are then applied on top of the include result.
// If only exclude is specified, all endpoints except the excluded ones are kept.
type Scope struct {
	Include []string `yaml:"include"`
	Exclude []string `yaml:"exclude"`
}

// loadScope parses a scope YAML file.
func loadScope(path string) (*Scope, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading scope %s: %w", path, err)
	}
	var s Scope
	if err := yaml.Unmarshal(data, &s); err != nil {
		return nil, fmt.Errorf("parsing scope %s: %w", path, err)
	}
	if len(s.Include) == 0 && len(s.Exclude) == 0 {
		return nil, fmt.Errorf("scope %s must have at least one include or exclude entry", path)
	}
	return &s, nil
}

// matchesAny reports whether name matches any pattern in the list.
// A pattern ending in '*' is a prefix match; otherwise it is an exact match.
func matchesAny(name string, patterns []string) bool {
	for _, p := range patterns {
		if strings.HasSuffix(p, "*") {
			if strings.HasPrefix(name, strings.TrimSuffix(p, "*")) {
				return true
			}
		} else if name == p {
			return true
		}
	}
	return false
}

// filterEndpoints applies the scope's include/exclude rules and returns the
// matching endpoints.
func (s *Scope) filterEndpoints(endpoints []Endpoint) []Endpoint {
	var out []Endpoint
	for _, e := range endpoints {
		included := len(s.Include) == 0 || matchesAny(e.Name, s.Include)
		excluded := len(s.Exclude) > 0 && matchesAny(e.Name, s.Exclude)
		if included && !excluded {
			out = append(out, e)
		}
	}
	return out
}
