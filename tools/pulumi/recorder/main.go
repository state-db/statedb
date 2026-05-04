package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	port       = flag.Int("port", 8080, "port to listen on")
	fixtureDir = flag.String("fixtures", "fixtures", "base directory for fixtures")
)

type server struct {
	rt        *router
	globalSeq int64
	mu        sync.Mutex
	cmdName   string
	cmdSeq    int64
}

type fixture struct {
	Seq        int64             `json:"seq"`
	Command    string            `json:"command,omitempty"`
	CmdSeq     int64             `json:"cmd_seq,omitempty"`
	Timestamp  string            `json:"timestamp"`
	Method     string            `json:"method"`
	Path       string            `json:"path"`
	Query      string            `json:"query,omitempty"`
	Headers    map[string]string `json:"headers"`
	Body       interface{}       `json:"body,omitempty"`
	BodyRaw    string            `json:"body_raw,omitempty"`
	StatusCode int               `json:"status_code"`
	Response   interface{}       `json:"response"`
}

func main() {
	flag.Parse()

	if err := os.MkdirAll(*fixtureDir, 0755); err != nil {
		log.Fatalf("creating fixture dir: %v", err)
	}

	s := &server{rt: newRouter()}
	http.HandleFunc("/", s.handle)

	addr := fmt.Sprintf(":%d", *port)
	log.Printf("recorder listening on %s  (fixtures → %s)", addr, *fixtureDir)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func (s *server) handle(w http.ResponseWriter, r *http.Request) {
	// Admin endpoint — not recorded as a fixture.
	if strings.HasPrefix(r.URL.Path, "/meta/") {
		s.handleMeta(w, r)
		return
	}

	n := atomic.AddInt64(&s.globalSeq, 1)

	body, _ := io.ReadAll(r.Body)
	r.Body.Close()

	var bodyJSON interface{}
	if len(body) > 0 {
		json.Unmarshal(body, &bodyJSON) //nolint:errcheck
	}

	headers := make(map[string]string, len(r.Header))
	for k, v := range r.Header {
		if strings.EqualFold(k, "authorization") {
			headers[k] = "REDACTED"
		} else {
			headers[k] = strings.Join(v, ", ")
		}
	}

	status, resp := s.rt.route(r.Method, r.URL.Path, body)

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
		Timestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		Method:     r.Method,
		Path:       r.URL.Path,
		Query:      r.URL.RawQuery,
		Headers:    headers,
		StatusCode: status,
		Response:   resp,
	}
	if bodyJSON != nil {
		fix.Body = bodyJSON
	} else if len(body) > 0 {
		fix.BodyRaw = string(body)
	}

	s.writeFixture(n, cmd, cmdSeq, r.Method, r.URL.Path, fix)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if resp != nil {
		json.NewEncoder(w).Encode(resp) //nolint:errcheck
	}
}

func (s *server) handleMeta(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/meta/command" || r.Method != "POST" {
		http.NotFound(w, r)
		return
	}
	var req struct {
		Command string `json:"command"`
	}
	json.NewDecoder(r.Body).Decode(&req) //nolint:errcheck
	r.Body.Close()

	s.mu.Lock()
	s.cmdName = req.Command
	s.cmdSeq = 0
	s.mu.Unlock()

	if req.Command != "" {
		os.MkdirAll(filepath.Join(*fixtureDir, "by-command", req.Command), 0755) //nolint:errcheck
		fmt.Printf("\n=== %s ===\n", req.Command)
	}
	w.WriteHeader(http.StatusOK)
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
