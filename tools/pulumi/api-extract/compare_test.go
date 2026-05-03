package main

import (
	"strings"
	"testing"
)

func TestCompareEndpointsAddedRemovedAndCounts(t *testing.T) {
	from := []Endpoint{
		{Method: "GET", Path: "/a", Name: "a", Category: "cat"},
		{Method: "POST", Path: "/b", Name: "b", Category: "cat"},
	}
	to := []Endpoint{
		{Method: "GET", Path: "/a", Name: "a", Category: "cat"},
		{Method: "DELETE", Path: "/c", Name: "c", Category: "other"},
	}

	report := compareEndpoints("v1", "v2", from, to)

	checks := []string{
		"| Endpoints in v1 | 2 |",
		"| Endpoints in v2 | 2 |",
		"| Added | 1 |",
		"| Removed | 1 |",
		"`DELETE /c`",
		"`POST /b`",
	}
	for _, c := range checks {
		if !strings.Contains(report, c) {
			t.Fatalf("report missing %q\n%s", c, report)
		}
	}
}

func TestCompareEndpointsNoChangesMessage(t *testing.T) {
	eps := []Endpoint{{Method: "GET", Path: "/a", Name: "a", Category: "cat"}}
	report := compareEndpoints("v1", "v2", eps, eps)
	if !strings.Contains(report, "No endpoint changes between these versions.") {
		t.Fatalf("expected no changes message\n%s", report)
	}
}
