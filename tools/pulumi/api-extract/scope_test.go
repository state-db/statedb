package main

import "testing"

func TestScopeFilterIncludeExclude(t *testing.T) {
	s := &Scope{
		Include: []string{"startUpdate_*", "getCurrentUser"},
		Exclude: []string{"startUpdate_destroy"},
	}
	endpoints := []Endpoint{
		{Name: "startUpdate_update"},
		{Name: "startUpdate_preview"},
		{Name: "startUpdate_destroy"},
		{Name: "getCurrentUser"},
		{Name: "other"},
	}

	got := s.filterEndpoints(endpoints)
	if len(got) != 3 {
		t.Fatalf("expected 3 endpoints after filtering, got %d", len(got))
	}

	seen := map[string]bool{}
	for _, e := range got {
		seen[e.Name] = true
	}
	for _, name := range []string{"startUpdate_update", "startUpdate_preview", "getCurrentUser"} {
		if !seen[name] {
			t.Fatalf("missing expected endpoint %q", name)
		}
	}
	if seen["startUpdate_destroy"] || seen["other"] {
		t.Fatalf("unexpected endpoint included")
	}
}

func TestScopeFilterExcludeOnly(t *testing.T) {
	s := &Scope{Exclude: []string{"drop*"}}
	endpoints := []Endpoint{{Name: "keep"}, {Name: "dropOne"}, {Name: "dropTwo"}}

	got := s.filterEndpoints(endpoints)
	if len(got) != 1 || got[0].Name != "keep" {
		t.Fatalf("unexpected filtered set: %#v", got)
	}
}
