package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

func loadEndpoints(snapshotsDir, ref string) ([]Endpoint, error) {
	data, err := os.ReadFile(filepath.Join(snapshotsDir, ref, "endpoints.json"))
	if err != nil {
		return nil, err
	}
	var endpoints []Endpoint
	if err := json.Unmarshal(data, &endpoints); err != nil {
		return nil, err
	}
	return endpoints, nil
}

func compareEndpoints(from, to string, fromEPs, toEPs []Endpoint) string {
	fromMap := make(map[string]Endpoint, len(fromEPs))
	for _, e := range fromEPs {
		fromMap[e.Key()] = e
	}
	toMap := make(map[string]Endpoint, len(toEPs))
	for _, e := range toEPs {
		toMap[e.Key()] = e
	}

	var added, removed []Endpoint
	for _, e := range toEPs {
		if _, exists := fromMap[e.Key()]; !exists {
			added = append(added, e)
		}
	}
	for _, e := range fromEPs {
		if _, exists := toMap[e.Key()]; !exists {
			removed = append(removed, e)
		}
	}

	sort.Slice(added, func(i, j int) bool { return added[i].Key() < added[j].Key() })
	sort.Slice(removed, func(i, j int) bool { return removed[i].Key() < removed[j].Key() })

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("# API Comparison: %s → %s\n\n", from, to))
	sb.WriteString(fmt.Sprintf("| | Count |\n|---|---|\n"))
	sb.WriteString(fmt.Sprintf("| Endpoints in %s | %d |\n", from, len(fromEPs)))
	sb.WriteString(fmt.Sprintf("| Endpoints in %s | %d |\n", to, len(toEPs)))
	sb.WriteString(fmt.Sprintf("| Added | %d |\n", len(added)))
	sb.WriteString(fmt.Sprintf("| Removed | %d |\n\n", len(removed)))

	if len(added) > 0 {
		sb.WriteString("## Added endpoints\n\n")
		for _, e := range added {
			sb.WriteString(fmt.Sprintf("- `%s %s` — %s (%s)\n", e.Method, e.Path, e.Name, e.Category))
		}
		sb.WriteString("\n")
	}

	if len(removed) > 0 {
		sb.WriteString("## Removed endpoints\n\n")
		for _, e := range removed {
			sb.WriteString(fmt.Sprintf("- `%s %s` — %s (%s)\n", e.Method, e.Path, e.Name, e.Category))
		}
		sb.WriteString("\n")
	}

	if len(added) == 0 && len(removed) == 0 {
		sb.WriteString("No endpoint changes between these versions.\n")
	}

	// Category breakdown for each version
	sb.WriteString(fmt.Sprintf("## Endpoints by category in %s\n\n", to))
	byCat := make(map[string][]Endpoint)
	for _, e := range toEPs {
		byCat[e.Category] = append(byCat[e.Category], e)
	}
	cats := make([]string, 0, len(byCat))
	for c := range byCat {
		cats = append(cats, c)
	}
	sort.Strings(cats)
	for _, cat := range cats {
		eps := byCat[cat]
		sb.WriteString(fmt.Sprintf("### %s (%d)\n\n", cat, len(eps)))
		sort.Slice(eps, func(i, j int) bool { return eps[i].Key() < eps[j].Key() })
		for _, e := range eps {
			sb.WriteString(fmt.Sprintf("- `%s %s`\n", e.Method, e.Path))
		}
		sb.WriteString("\n")
	}

	return sb.String()
}
