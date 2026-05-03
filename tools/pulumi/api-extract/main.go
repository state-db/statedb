// Package main is a CLI tool for extracting and tracking the Pulumi API surface.
//
// Usage:
//
//	go run . extract --ref=v3.234.0
//	go run . compare --from=v3.220.0 --to=v3.234.0
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, "Usage: %s <extract|compare> [flags]\n\n", os.Args[0])
		fmt.Fprintf(os.Stderr, "  extract --ref=<git-ref>              Extract endpoints for a Pulumi version\n")
		fmt.Fprintf(os.Stderr, "  compare --from=<ref> --to=<ref>      Diff two extracted versions\n")
		os.Exit(1)
	}

	switch os.Args[1] {
	case "extract":
		runExtract(os.Args[2:])
	case "compare":
		runCompare(os.Args[2:])
	default:
		fmt.Fprintf(os.Stderr, "unknown subcommand: %s\n", os.Args[1])
		os.Exit(1)
	}
}

func runExtract(args []string) {
	fs := flag.NewFlagSet("extract", flag.ExitOnError)
	ref := fs.String("ref", "", "Pulumi git ref (tag, branch, or commit SHA)")
	outDir := fs.String("output", "", "Output directory (default: api-snapshots/pulumi/<ref>)")
	scopePath := fs.String("scope", "", "Scope YAML file to filter openapi-subset.yaml (optional)")
	fs.Parse(args) //nolint:errcheck

	if *ref == "" {
		fmt.Fprintln(os.Stderr, "error: --ref is required")
		os.Exit(1)
	}

	dir := *outDir
	if dir == "" {
		dir = filepath.Join("api-snapshots", "pulumi", *ref)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "error creating output dir: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Fetching Pulumi source files at ref %s...\n", *ref)
	apiEndpointsGo, err := fetchFileAtRef(pulumiOwner, pulumiRepo,
		"pkg/backend/httpstate/client/api_endpoints.go", *ref)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
	apiGo, err := fetchFileAtRef(pulumiOwner, pulumiRepo,
		"pkg/backend/httpstate/client/api.go", *ref)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}

	endpoints := extractEndpoints(apiEndpointsGo)
	apiVersion := extractAPIVersion(apiGo)
	fmt.Printf("Extracted %d endpoints (Accept: %s)\n", len(endpoints), apiVersion)

	fmt.Println("Fetching Pulumi OpenAPI spec...")
	spec, specSource, err := fetchOpenAPISpec(*ref)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("OpenAPI source: %s (%s)\n", specSource.Type, specSource.Location)
	if specSource.Type == "live-fallback" {
		fmt.Fprintf(os.Stderr,
			"warning: using live OpenAPI fallback; output may not be fully reproducible for this ref (fixture reason: %s)\n",
			specSource.FallbackReason)
	}

	// Enrich categories from spec tags before writing endpoints.json.
	// Endpoints not matched in the spec keep the path-heuristic category.
	endpoints = enrichEndpoints(spec, endpoints)

	endpointsJSON, err := json.MarshalIndent(endpoints, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
	mustWrite(filepath.Join(dir, "endpoints.json"), endpointsJSON)

	// If a scope is given, filter which endpoints go into the OpenAPI subset.
	// endpoints.json always contains the full set.
	specEndpoints := endpoints
	if *scopePath != "" {
		scope, err := loadScope(*scopePath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "error: %v\n", err)
			os.Exit(1)
		}
		specEndpoints = scope.filterEndpoints(endpoints)
		fmt.Printf("Scope %s: %d/%d endpoints included in spec subset\n",
			*scopePath, len(specEndpoints), len(endpoints))
	}

	filtered := filterSpec(spec, specEndpoints)
	matched := specPathCount(filtered)
	fmt.Printf("Matched %d/%d scoped endpoints in OpenAPI spec\n", matched, len(specEndpoints))

	yamlBytes, err := marshalYAML(filtered)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error marshaling YAML: %v\n", err)
		os.Exit(1)
	}
	mustWrite(filepath.Join(dir, "openapi-subset.yaml"), yamlBytes)

	meta := map[string]interface{}{
		"pulumi_ref":         *ref,
		"api_accept_version": apiVersion,
		"openapi_source":     specSource.Type,
		"openapi_location":   specSource.Location,
		"openapi_sha256":     specSource.SHA256,
		"openapi_fallback_reason": specSource.FallbackReason,
		"extracted_at":       time.Now().UTC().Format(time.RFC3339),
		"total_endpoints":    len(endpoints),
		"matched_in_spec":    matched,
	}
	metaJSON, _ := json.MarshalIndent(meta, "", "  ")
	mustWrite(filepath.Join(dir, "meta.json"), metaJSON)

	fmt.Printf("\nDone. Output in %s/\n", dir)
	fmt.Printf("  %s/endpoints.json       — %d endpoints\n", dir, len(endpoints))
	fmt.Printf("  %s/openapi-subset.yaml  — %d matched paths with schemas\n", dir, matched)
	fmt.Printf("  %s/meta.json\n", dir)
}

func runCompare(args []string) {
	fs := flag.NewFlagSet("compare", flag.ExitOnError)
	from := fs.String("from", "", "From version (e.g. v3.220.0)")
	to := fs.String("to", "", "To version (e.g. v3.234.0)")
	snapshotsDir := fs.String("snapshots-dir", "api-snapshots/pulumi", "Directory containing version snapshot subdirectories")
	fs.Parse(args) //nolint:errcheck

	if *from == "" || *to == "" {
		fmt.Fprintln(os.Stderr, "error: --from and --to are required")
		os.Exit(1)
	}

	fromEPs, err := loadEndpoints(*snapshotsDir, *from)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error loading %s: %v\n", *from, err)
		os.Exit(1)
	}
	toEPs, err := loadEndpoints(*snapshotsDir, *to)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error loading %s: %v\n", *to, err)
		os.Exit(1)
	}

	report := compareEndpoints(*from, *to, fromEPs, toEPs)

	comparisonsDir := filepath.Join(*snapshotsDir, "comparisons")
	if err := os.MkdirAll(comparisonsDir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "error creating comparisons dir: %v\n", err)
		os.Exit(1)
	}
	outFile := filepath.Join(comparisonsDir, fmt.Sprintf("compare-%s-%s.md", *from, *to))
	mustWrite(outFile, []byte(report))
	fmt.Printf("Comparison written to %s\n\n", outFile)
	fmt.Print(report)
}

func mustWrite(path string, data []byte) {
	if err := os.WriteFile(path, data, 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "error writing %s: %v\n", path, err)
		os.Exit(1)
	}
}
