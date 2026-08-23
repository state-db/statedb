package main

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	port       = flag.Int("port", 8080, "port to listen on")
	fixtureDir = flag.String("fixtures", "fixtures", "base directory for fixtures")
)

var uuidRe = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)
var cliVersionRe = regexp.MustCompile(`v3\.\d+\.\d+`)
var exactCliVersionRe = regexp.MustCompile(`^v3\.\d+\.\d+$`)

type server struct {
	rt        *router
	globalSeq int64
	mu        sync.Mutex
	cmdName   string
	cmdSeq    int64
	seenCmds  map[string]bool
}

type fixture struct {
	Seq        int64             `json:"seq"`
	Command    string            `json:"command,omitempty"`
	CmdSeq     int64             `json:"cmd_seq,omitempty"`
	Method     string            `json:"method"`
	Path       string            `json:"path"`
	Query      string            `json:"query,omitempty"`
	Headers    map[string]string `json:"headers"`
	Body       interface{}       `json:"body,omitempty"`
	BodyB64    string            `json:"body_b64,omitempty"`  // replay request bytes, base64-encoded (binary-safe)
	BodyGzip   bool              `json:"body_gzip,omitempty"` // true if body_b64 is gzip-compressed
	BodyRaw    string            `json:"body_raw,omitempty"`  // legacy: lossy UTF-8 of bytes — only set if not parseable as JSON and not gzip
	StatusCode int               `json:"status_code"`
	Response   interface{}       `json:"response"`
}

func main() {
	flag.Parse()

	if err := os.MkdirAll(*fixtureDir, 0755); err != nil {
		log.Fatalf("creating fixture dir: %v", err)
	}

	s := &server{rt: newRouter(), seenCmds: make(map[string]bool)}
	http.HandleFunc("/", s.handle)

	addr := fmt.Sprintf(":%d", *port)
	log.Printf("recorder listening on %s  (fixtures → %s)", addr, *fixtureDir)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func (s *server) handle(w http.ResponseWriter, r *http.Request) {
	// Admin endpoints — not recorded as fixtures.
	if strings.HasPrefix(r.URL.Path, "/meta/") {
		s.handleMeta(w, r)
		return
	}

	stabilizeConcurrentPulumiProbe(r.URL.Path)
	n := atomic.AddInt64(&s.globalSeq, 1)

	body, _ := io.ReadAll(r.Body)
	r.Body.Close()

	// Decompress gzip-encoded bodies (checkpoint, events/batch, journalentries) so the
	// parsed JSON appears in `body`. Replay bytes are preserved in `body_b64` so the
	// contract harness can send gzip payloads back to the app.
	var bodyJSON interface{}
	var decompressed []byte
	isGzip := strings.EqualFold(r.Header.Get("Content-Encoding"), "gzip")
	if isGzip && len(body) > 0 {
		if zr, err := gzip.NewReader(bytes.NewReader(body)); err == nil {
			if buf, err := io.ReadAll(zr); err == nil {
				decompressed = buf
			}
			zr.Close()
		}
	}
	switch {
	case decompressed != nil:
		json.Unmarshal(decompressed, &bodyJSON) //nolint:errcheck
	case len(body) > 0:
		json.Unmarshal(body, &bodyJSON) //nolint:errcheck
	}
	if bodyJSON != nil {
		bodyJSON = normalizeFixtureJSON(bodyJSON)
	}

	headers := make(map[string]string, len(r.Header))
	for k, v := range r.Header {
		if strings.EqualFold(k, "authorization") {
			headers[k] = "REDACTED"
		} else if strings.EqualFold(k, "user-agent") {
			// The cmd= token is meaningful signal (it renames when the CLI
			// renames commands); the CLI version is churn that would dirty
			// every fixture on a pin bump. meta.json records the version.
			headers[k] = cliVersionRe.ReplaceAllString(strings.Join(v, ", "), "[cli-version]")
		} else {
			headers[k] = strings.Join(v, ", ")
		}
	}

	routeBody := body
	if decompressed != nil {
		routeBody = decompressed
	}
	status, resp := s.rt.route(r.Method, r.URL.Path, routeBody)
	fixtureResp := normalizeFixtureJSON(resp)

	s.mu.Lock()
	cmd := s.cmdName
	s.cmdSeq++
	cmdSeq := s.cmdSeq
	s.mu.Unlock()

	fmt.Printf("[%04d] %s %s → %d\n", n, r.Method, r.URL.RequestURI(), status)

	fix := fixture{
		Seq:        n,
		Command:    cmd,
		CmdSeq:     cmdSeq,
		Method:     r.Method,
		Path:       r.URL.Path,
		Query:      r.URL.RawQuery,
		Headers:    headers,
		StatusCode: status,
		Response:   fixtureResp,
	}
	if bodyJSON != nil {
		fix.Body = bodyJSON
	}
	if len(body) > 0 {
		replayBody := replayBodyBytes(body, bodyJSON, isGzip)
		headers["Content-Length"] = fmt.Sprintf("%d", len(replayBody))
		// Preserve replay bytes losslessly. base64 is small overhead and lets the
		// contract harness exercise gzip payload handling without diffing volatile
		// timestamps from the CLI's event stream.
		fix.BodyB64 = base64.StdEncoding.EncodeToString(replayBody)
		fix.BodyGzip = isGzip
		if bodyJSON == nil && !isGzip {
			// Non-JSON, non-gzip — keep the legacy lossy view for human-readability only.
			fix.BodyRaw = string(body)
		}
	}

	s.writeFixture(n, cmd, cmdSeq, r.Method, r.URL.Path, fix)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if resp != nil {
		json.NewEncoder(w).Encode(resp) //nolint:errcheck
	}
}

func (s *server) handleMeta(w http.ResponseWriter, r *http.Request) {
	// Readiness probe used by entrypoint.sh instead of a fixed sleep.
	if r.URL.Path == "/meta/health" && r.Method == http.MethodGet {
		w.WriteHeader(http.StatusOK)
		return
	}
	if r.Method != "POST" {
		http.NotFound(w, r)
		return
	}
	switch r.URL.Path {
	case "/meta/command":
		s.handleMetaCommand(w, r)
	case "/meta/active-update":
		s.handleMetaActiveUpdate(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (s *server) handleMetaCommand(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Command string `json:"command"`
	}
	json.NewDecoder(r.Body).Decode(&req) //nolint:errcheck
	r.Body.Close()

	s.mu.Lock()
	if s.seenCmds[req.Command] {
		s.mu.Unlock()
		http.Error(w, "command label already used: "+req.Command, http.StatusConflict)
		return
	}
	s.seenCmds[req.Command] = true
	s.cmdName = req.Command
	s.cmdSeq = 0
	s.mu.Unlock()

	if req.Command != "" {
		os.MkdirAll(filepath.Join(*fixtureDir, "by-command", req.Command), 0755) //nolint:errcheck
		fmt.Printf("\n=== %s ===\n", req.Command)
	}
	w.WriteHeader(http.StatusOK)
}

// Seeds an in-flight update on a stack — the precondition `pulumi cancel` needs and that no
// pulumi command can leave behind, since every update the CLI starts runs to completion. It
// lives here rather than in entrypoint.sh's command sequence so the fabricated state never
// lands in by-command/ as if a CLI had produced it.
func (s *server) handleMetaActiveUpdate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Stack string `json:"stack"`
	}
	json.NewDecoder(r.Body).Decode(&req) //nolint:errcheck
	r.Body.Close()

	updateID, ok := s.rt.seedActiveUpdate(req.Stack)
	if !ok {
		http.Error(w, "unknown stack: "+req.Stack, http.StatusNotFound)
		return
	}

	fmt.Printf("\n=== seeded active update %s on %s ===\n", updateID, req.Stack)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"updateID": updateID}) //nolint:errcheck
}

func (s *server) writeFixture(globalN int64, cmd string, cmdSeq int64, method, path string, fix fixture) {
	data, err := json.MarshalIndent(fix, "", "  ")
	if err != nil {
		return
	}
	slug := filenameSlug(path)

	if cmd != "" {
		cmdFile := fmt.Sprintf("%04d_%s_%s.json", cmdSeq, method, slug)
		os.WriteFile(filepath.Join(*fixtureDir, "by-command", cmd, cmdFile), data, 0644) //nolint:errcheck
	} else {
		// Before any command signal — write to root so nothing is lost.
		ungrouped := fmt.Sprintf("%04d_%s_%s.json", globalN, method, slug)
		os.WriteFile(filepath.Join(*fixtureDir, ungrouped), data, 0644) //nolint:errcheck
	}
}

func filenameSlug(path string) string {
	slug := strings.ReplaceAll(strings.Trim(path, "/"), "/", "_")
	if len(slug) > 120 {
		slug = slug[:120]
	}
	return slug
}

func replayBodyBytes(original []byte, bodyJSON interface{}, isGzip bool) []byte {
	if bodyJSON == nil {
		return original
	}
	data, err := json.Marshal(bodyJSON)
	if err != nil {
		return original
	}
	if !isGzip {
		return data
	}
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if _, err := zw.Write(data); err != nil {
		zw.Close() //nolint:errcheck
		return original
	}
	if err := zw.Close(); err != nil {
		return original
	}
	return buf.Bytes()
}

func normalizeFixtureJSON(v interface{}) interface{} {
	return normalizeFixtureJSONValue("", v)
}

func normalizeFixtureJSONValue(key string, v interface{}) interface{} {
	switch x := v.(type) {
	case map[string]interface{}:
		out := make(map[string]interface{}, len(x))
		for k, val := range x {
			if key == "plaintexts" {
				out["[secret]"] = "[secret]"
				continue
			}
			switch k {
			case "timestamp", "durationSeconds":
				out[k] = float64(0)
				continue
			case "created", "modified":
				out[k] = "1970-01-01T00:00:00Z"
				continue
			}
			out[k] = normalizeFixtureJSONValue(k, val)
		}
		return out
	case map[string]string:
		out := make(map[string]interface{}, len(x))
		for k, val := range x {
			if key == "plaintexts" {
				out["[secret]"] = "[secret]"
				continue
			}
			out[k] = normalizeFixtureString(k, val)
		}
		return out
	case []interface{}:
		out := make([]interface{}, len(x))
		for i, val := range x {
			out[i] = normalizeFixtureJSONValue(key, val)
		}
		return out
	case string:
		return normalizeFixtureString(key, x)
	default:
		return v
	}
}

func normalizeFixtureString(key, value string) string {
	value = uuidRe.ReplaceAllString(value, "00000000-0000-0000-0000-000000000000")
	// Provider references embed the provider resource's id after "::" (e.g.
	// "urn:...::pulumi:providers:random::default::<uuid>"). The id field itself
	// normalizes to "[resource-id]" below, so normalize the embedded copy the
	// same way — otherwise the fixture deployment fails Pulumi's
	// unknown-provider integrity rule (see tools/pulumi/verify).
	value = strings.ReplaceAll(
		value, "::00000000-0000-0000-0000-000000000000", "::[resource-id]")
	switch key {
	case "pulumi.version":
		// The CLI stamps its own version into the update environment, which is
		// pure version-bump churn; the exact version lives in meta.json.
		//
		// Deliberately request-side only. Manifest `version` values come back
		// from the stub as a fixed constant (paired with the manifest magic),
		// so they never churn — and recorded responses are the contract
		// ContractReplayTest asserts state-db against, where a placeholder
		// would be unmatchable.
		if exactCliVersionRe.MatchString(value) {
			return "[cli-version]"
		}
	case "b64Std":
		if value != "" {
			return "[b64-std]"
		}
	case "b64Url":
		if value != "" {
			return "[b64-url]"
		}
	case "ciphertext", "ciphertexts", "password", "plaintext":
		if value != "" {
			return "[secret]"
		}
	case "dec":
		if value != "" {
			return "[decimal-id]"
		}
	case "hex", "tokenHex":
		if value != "" {
			return "[hex]"
		}
	case "id":
		if value != "" {
			return "[resource-id]"
		}
	case "salt":
		// Passphrase-provider salt is freshly generated per capture session.
		if value != "" {
			return "[salt]"
		}
	case "result":
		if value != "" && value != "succeeded" && value != "failed" && value != "cancelled" {
			return "[result]"
		}
	case "petName":
		if value != "" {
			return "[pet-name]"
		}
	case "sourcePosition":
		// Engine-event diagnostic positions embed toolchain module paths
		// (grpc/sdk versions, pseudo-versions) that churn on every CLI or
		// provider rebuild.
		if value != "" {
			return "[source-position]"
		}
	case "word":
		if value != "" {
			return "[word]"
		}
	}
	return value
}

func stabilizeConcurrentPulumiProbe(path string) {
	if path == "/api/user/organizations/default" {
		time.Sleep(15 * time.Millisecond)
	}
	if strings.HasSuffix(path, "/export") {
		time.Sleep(20 * time.Millisecond)
	}
	if strings.HasSuffix(path, "/batch-decrypt") {
		time.Sleep(40 * time.Millisecond)
	}
	// The CLI fires encrypt concurrently with journal writes during destroy;
	// delay it so journalentries lands first deterministically.
	if strings.HasSuffix(path, "/encrypt") {
		time.Sleep(25 * time.Millisecond)
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 6 && parts[0] == "api" && parts[1] == "stacks" {
		switch parts[5] {
		case "preview", "update", "destroy", "refresh":
			time.Sleep(20 * time.Millisecond)
		}
	}
	if len(parts) == 5 && parts[0] == "api" && parts[1] == "stacks" {
		time.Sleep(30 * time.Millisecond)
	}
}
