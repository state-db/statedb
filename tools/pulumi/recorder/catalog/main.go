// catalog reads fixtures from fixtures/by-command/ and writes fixtures/catalog.md —
// a deduplicated, human-readable API contract reference for state-db implementors.
package main

import (
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

var (
	fixtures = flag.String("fixtures", "fixtures", "fixtures base directory (contains by-command/)")
	out      = flag.String("out", "", "output path for catalog.md (default: {fixtures}/catalog.md)")
	org      = flag.String("org", "testorg", "org name used during recording (for path normalization)")
	project  = flag.String("project", "recorder-test", "project name used during recording")
	stack    = flag.String("stack", "dev", "stack name used during recording")
)

var updateIDRe = regexp.MustCompile(`^(preview|update|destroy|refresh)-\d+$`)

type rawFixture struct {
	Seq        int64             `json:"seq"`
	Command    string            `json:"command"`
	CmdSeq     int64             `json:"cmd_seq"`
	Method     string            `json:"method"`
	Path       string            `json:"path"`
	Query      string            `json:"query"`
	Headers    map[string]string `json:"headers"`
	Body       interface{}       `json:"body"`
	BodyB64    string            `json:"body_b64"`
	BodyGzip   bool              `json:"body_gzip"`
	BodyRaw    string            `json:"body_raw"`
	StatusCode int               `json:"status_code"`
	Response   interface{}       `json:"response"`
}

type endpointKey struct {
	Method   string
	NormPath string
}

type endpointEntry struct {
	key      endpointKey
	commands []string // ordered, deduped command names
	cmdSet   map[string]bool
	count    int
	example  *rawFixture // first fixture seen (canonical example)
}

func main() {
	flag.Parse()

	byCmd := filepath.Join(*fixtures, "by-command")
	cmdOrder := commandOrder(byCmd)
	cmdFixtures := loadAllFixtures(byCmd, cmdOrder)
	entries := collectEntries(cmdOrder, cmdFixtures)

	outPath := *out
	if outPath == "" {
		outPath = filepath.Join(*fixtures, "catalog.md")
	}
	if err := os.MkdirAll(filepath.Dir(outPath), 0755); err != nil {
		fmt.Fprintf(os.Stderr, "creating output dir: %v\n", err)
		os.Exit(1)
	}
	f, err := os.Create(outPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "creating catalog: %v\n", err)
		os.Exit(1)
	}
	defer f.Close()

	write := func(format string, args ...interface{}) {
		fmt.Fprintf(f, format, args...)
	}

	write("# Pulumi HTTP Backend — API Catalog\n\n")
	write("> Generated from `%s/by-command/`\n", fixtureDisplayPath(*fixtures))
	write(">\n")
	write("> **Recording values:** org=`%s` · project=`%s` · stack=`%s`\n", *org, *project, *stack)
	write(">\n")
	write("> Path parameters are shown as `{org}`, `{project}`, `{stack}`, `{updateID}`.\n\n")

	write("## Endpoint Index\n\n")
	write("| # | Method | Path | Calls | Commands |\n")
	write("|---|--------|------|-------|----------|\n")
	for i, e := range entries {
		write("| %d | `%s` | `%s` | %d | %s |\n", i+1, e.key.Method, e.key.NormPath, e.count, abbreviateCommands(e.commands))
	}
	write("\n---\n\n")

	write("## Call Sequences by Command\n\n")
	for _, cmd := range cmdOrder {
		write("### `%s`\n\n", stripCmdPrefix(cmd))
		write("```\n")
		for _, fx := range cmdFixtures[cmd] {
			q := ""
			if fx.Query != "" {
				q = "?" + fx.Query
			}
			write("%s %s%s\n", fx.Method, normalizePath(fx.Path, *org, *project, *stack), q)
		}
		write("```\n\n")
	}
	write("---\n\n")

	write("## Endpoint Details\n\n")
	for i, e := range entries {
		q := ""
		if e.example.Query != "" {
			q = "?" + e.example.Query
		}
		write("### %d. `%s %s%s`\n\n", i+1, e.key.Method, e.key.NormPath, q)
		write("**Calls:** %d &nbsp;·&nbsp; **Commands:** %s\n\n", e.count, commandBadges(e.commands))

		if hasRequestBody(e.example) {
			write("**Request body:**\n\n```json\n%s\n```\n\n", prettyRequestBody(e.example))
			if e.example.BodyB64 != "" {
				write("%s\n\n", wireBodyNote(e.example))
			}
		}

		write("**Response** (`%d`):\n\n```json\n%s\n```\n\n", e.example.StatusCode, prettyJSON(e.example.Response, ""))
		write("---\n\n")
	}

	fmt.Printf("==> catalog written to %s (%d unique endpoints)\n", outPath, len(entries))
}

func loadAllFixtures(byCmd string, order []string) map[string][]*rawFixture {
	m := make(map[string][]*rawFixture, len(order))
	for _, cmd := range order {
		m[cmd] = sequenceForCommand(byCmd, cmd)
	}
	return m
}

func fixtureDisplayPath(path string) string {
	if filepath.IsAbs(path) {
		return filepath.Base(path)
	}
	return path
}

// collectEntries deduplicates by METHOD+path, ordered by first appearance.
// The canonical example per endpoint prefers a 2xx response over non-2xx.
func collectEntries(order []string, cmdFixtures map[string][]*rawFixture) []*endpointEntry {
	index := map[endpointKey]*endpointEntry{}
	var keys []endpointKey

	for _, cmd := range order {
		for _, fx := range cmdFixtures[cmd] {
			norm := normalizePath(fx.Path, *org, *project, *stack)
			k := endpointKey{Method: fx.Method, NormPath: norm}
			e, exists := index[k]
			if !exists {
				e = &endpointEntry{key: k, cmdSet: map[string]bool{}, example: fx}
				index[k] = e
				keys = append(keys, k)
			}
			e.count++
			if !e.cmdSet[cmd] {
				e.cmdSet[cmd] = true
				e.commands = append(e.commands, cmd)
			}
			// Prefer a 2xx response as the canonical example.
			if fx.StatusCode >= 200 && fx.StatusCode < 300 &&
				(e.example.StatusCode < 200 || e.example.StatusCode >= 300) {
				e.example = fx
			}
		}
	}

	out := make([]*endpointEntry, len(keys))
	for i, k := range keys {
		out[i] = index[k]
	}
	return out
}

func commandOrder(byCmd string) []string {
	entries, err := os.ReadDir(byCmd)
	if err != nil {
		return nil
	}
	var dirs []string
	for _, e := range entries {
		if e.IsDir() {
			dirs = append(dirs, e.Name())
		}
	}
	sort.Strings(dirs)
	return dirs
}

func sequenceForCommand(byCmd, cmd string) []*rawFixture {
	dir := filepath.Join(byCmd, cmd)
	files, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	sort.Slice(files, func(i, j int) bool {
		return files[i].Name() < files[j].Name()
	})

	var out []*rawFixture
	for _, f := range files {
		if !strings.HasSuffix(f.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, f.Name()))
		if err != nil {
			continue
		}
		var fx rawFixture
		if err := json.Unmarshal(data, &fx); err != nil {
			continue
		}
		out = append(out, &fx)
	}
	return out
}

func normalizePath(raw, orgVal, projectVal, stackVal string) string {
	parts := strings.Split(strings.Trim(raw, "/"), "/")
	for i, p := range parts {
		switch {
		case p == orgVal:
			parts[i] = "{org}"
		case p == projectVal:
			parts[i] = "{project}"
		case p == stackVal:
			parts[i] = "{stack}"
		case updateIDRe.MatchString(p):
			parts[i] = "{updateID}"
		}
	}
	return "/" + strings.Join(parts, "/")
}

func hasRequestBody(fx *rawFixture) bool {
	return fx.Body != nil || fx.BodyRaw != "" || fx.BodyB64 != ""
}

func prettyRequestBody(fx *rawFixture) string {
	if fx.Body != nil || fx.BodyRaw != "" {
		return prettyJSON(fx.Body, fx.BodyRaw)
	}
	if fx.BodyB64 == "" {
		return "null"
	}
	b, err := base64.StdEncoding.DecodeString(fx.BodyB64)
	if err != nil {
		return "// body_b64 is not valid base64"
	}
	if fx.BodyGzip {
		return fmt.Sprintf("// gzip-compressed binary payload (%d bytes) preserved in body_b64 for replay", len(b))
	}
	if !isPrintableBytes(b) {
		return fmt.Sprintf("// binary payload (%d bytes) preserved in body_b64 for replay", len(b))
	}
	return string(b)
}

func wireBodyNote(fx *rawFixture) string {
	b, err := base64.StdEncoding.DecodeString(fx.BodyB64)
	if err != nil {
		return "_Wire request body is present in `body_b64`, but it could not be decoded._"
	}
	if fx.BodyGzip {
		return fmt.Sprintf("_Replay request body preserved in `body_b64` (%d gzip bytes)._", len(b))
	}
	return fmt.Sprintf("_Replay request body preserved in `body_b64` (%d bytes)._", len(b))
}

func prettyJSON(v interface{}, raw string) string {
	if v == nil && raw == "" {
		return "null"
	}
	if v == nil {
		// raw may be binary (e.g. gzip-compressed checkpoint/events payloads).
		if !isPrintable(raw) {
			return fmt.Sprintf("// binary payload (%d bytes) — likely gzip-compressed", len(raw))
		}
		return raw
	}
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return fmt.Sprintf("%v", v)
	}
	return string(b)
}

func isPrintable(s string) bool {
	return isPrintableBytes([]byte(s))
}

func isPrintableBytes(b []byte) bool {
	for i := 0; i < len(b) && i < 512; i++ {
		c := b[i]
		if c < 0x09 || (c > 0x0d && c < 0x20) || c == 0x7f {
			return false
		}
	}
	return true
}

func stripCmdPrefix(cmd string) string {
	return strings.TrimLeft(cmd, "0123456789-")
}

func abbreviateCommands(cmds []string) string {
	var out []string
	for _, c := range cmds {
		out = append(out, stripCmdPrefix(c))
	}
	return strings.Join(out, ", ")
}

func commandBadges(cmds []string) string {
	var out []string
	for _, c := range cmds {
		out = append(out, "`"+c+"`")
	}
	return strings.Join(out, " ")
}
