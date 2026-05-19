package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var opCounter int64

type opState struct {
	token string
}

type stackInfo struct {
	orgName     string
	projectName string
	stackName   string
	tags        map[string]interface{}
}

type router struct {
	mu     sync.Mutex
	ops    map[string]opState  // updateID → lease state
	stacks map[string]stackInfo // "org/proj/stack" → stack record
}

func newRouter() *router {
	return &router{
		ops:    make(map[string]opState),
		stacks: make(map[string]stackInfo),
	}
}

func stackKey(org, proj, stack string) string {
	return org + "/" + proj + "/" + stack
}

// route matches method+path to a stub response. Returns HTTP status and body.
// Path segments (after stripping leading "/") are referenced as parts[0..n-1]:
//
//	/api/stacks/{org}/{proj}/{stack}/{op}/{updateID}/{subOp}/{leaf}
//	  0    1      2    3      4       5      6         7      8
func (rt *router) route(method, path string, body []byte) (int, interface{}) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	n := len(parts)

	if n < 2 || parts[0] != "api" {
		return 404, errBody("unknown path", path)
	}

	switch parts[1] {
	case "capabilities":
		return rt.routeCapabilities(method, parts, n)
	case "user":
		return rt.routeUser(method, parts, n)
	case "stacks":
		return rt.routeStacks(method, parts, n, body)
	case "orgs":
		return rt.routeOrgs(method, parts, n)
	}

	return 404, errBody("unhandled", path)
}

// --- /api/capabilities ---

func (rt *router) routeCapabilities(method string, _ []string, _ int) (int, interface{}) {
	if method != "GET" {
		return 405, nil
	}
	return 200, map[string]interface{}{
		"capabilities": []map[string]interface{}{
			{"name": "delta-checkpoint-updates", "version": 1},
		},
	}
}

// --- /api/user[/...] ---

func (rt *router) routeUser(method string, parts []string, n int) (int, interface{}) {
	switch {
	case n == 2 && method == "GET":
		return 200, map[string]interface{}{
			"githubLogin": "testorg",
			"name":        "Test User",
			"email":       "test@example.com",
			"organizations": []map[string]interface{}{
				{"githubLogin": "testorg", "name": "testorg"},
			},
		}
	case n == 3 && parts[2] == "stacks" && method == "GET":
		return 200, map[string]interface{}{"stacks": []interface{}{}}
	case n == 4 && parts[2] == "organizations" && parts[3] == "default" && method == "GET":
		return 200, map[string]interface{}{
			"organization": map[string]interface{}{
				"githubLogin": "testorg",
				"name":        "testorg",
			},
		}
	}
	return 404, errBody("unhandled user path", strings.Join(parts, "/"))
}

// --- /api/orgs[/...] ---

func (rt *router) routeOrgs(_ string, _ []string, _ int) (int, interface{}) {
	return 200, map[string]interface{}{}
}

// --- /api/stacks[/...] ---

func (rt *router) routeStacks(method string, parts []string, n int, body []byte) (int, interface{}) {
	org, proj, stack, op, updateID, subOp := "", "", "", "", "", ""
	if n > 2 {
		org = parts[2]
	}
	if n > 3 {
		proj = parts[3]
	}
	if n > 4 {
		stack = parts[4]
	}
	if n > 5 {
		op = parts[5]
	}
	if n > 6 {
		updateID = parts[6]
	}
	if n > 7 {
		subOp = parts[7]
	}
	switch {
	// GET /api/stacks/{org} — list org stacks
	case n == 3 && method == "GET":
		return rt.listStacks(org)

	// POST /api/stacks/{org}/{proj} — create stack (project in URL, stackName in body)
	// HEAD /api/stacks/{org}/{proj} — project existence check
	case n == 4:
		switch method {
		case "POST":
			return rt.createStack(org, proj, body)
		case "HEAD":
			return 200, nil
		}

	// CRUD on /api/stacks/{org}/{proj}/{stack}
	case n == 5:
		switch method {
		case "GET":
			return rt.getStack(org, proj, stack)
		case "DELETE":
			return rt.deleteStack(org, proj, stack)
		}

	// Operations directly on a stack: /api/stacks/{org}/{proj}/{stack}/{op}
	case n == 6:
		switch op {
		case "export":
			if method == "GET" {
				return rt.exportStack()
			}
		case "import":
			if method == "POST" {
				return 200, map[string]interface{}{}
			}
		case "rename":
			if method == "POST" {
				return 200, map[string]interface{}{}
			}
		case "updates":
			if method == "GET" {
				return 200, map[string]interface{}{"updates": []interface{}{}, "itemsPerPage": 10}
			}
		case "tags":
			if method == "PATCH" {
				return 200, map[string]interface{}{}
			}
		case "preview", "update", "destroy", "refresh":
			if method == "POST" {
				return rt.createOp(op)
			}
		case "encrypt":
			if method == "POST" {
				return rt.encryptValue(body)
			}
		case "decrypt":
			if method == "POST" {
				return rt.decryptValue(body)
			}
		case "batch-decrypt":
			if method == "POST" {
				return rt.batchDecrypt(body)
			}
		}

	// Start an operation / status: /api/stacks/{org}/{proj}/{stack}/{kind}/{updateID}
	case n == 7:
		switch method {
		case "POST":
			return rt.startOp(updateID, body)
		case "GET":
			return 200, map[string]interface{}{"status": "running", "updateID": updateID}
		}

	// Sub-operations: /api/stacks/{org}/{proj}/{stack}/{kind}/{updateID}/{subOp}[/leaf]
	case n >= 8:
		switch subOp {
		case "complete":
			rt.mu.Lock()
			delete(rt.ops, updateID)
			rt.mu.Unlock()
			return 200, map[string]interface{}{}
		case "renew_lease":
			return rt.renewLease()
		case "checkpoint", "checkpointdelta", "checkpointverbatim", "journalentries":
			return 200, map[string]interface{}{}
		case "events":
			return 200, map[string]interface{}{}
		}
	}

	return 404, errBody("unhandled stacks path", fmt.Sprintf("%s %s", method, strings.Join(parts, "/")))
}

// --- stack CRUD helpers ---

func (rt *router) listStacks(org string) (int, interface{}) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	var out []interface{}
	for _, s := range rt.stacks {
		if s.orgName == org {
			out = append(out, rt.stackBody(s))
		}
	}
	if out == nil {
		out = []interface{}{}
	}
	return 200, map[string]interface{}{"stacks": out}
}

func (rt *router) createStack(org, proj string, body []byte) (int, interface{}) {
	var req map[string]interface{}
	json.Unmarshal(body, &req) //nolint:errcheck
	stackName, _ := req["stackName"].(string)
	tags, _ := req["tags"].(map[string]interface{})
	if tags == nil {
		tags = map[string]interface{}{}
	}
	info := stackInfo{orgName: org, projectName: proj, stackName: stackName, tags: tags}
	rt.mu.Lock()
	rt.stacks[stackKey(org, proj, stackName)] = info
	rt.mu.Unlock()
	return 200, rt.stackBody(info)
}

func (rt *router) getStack(org, proj, stack string) (int, interface{}) {
	rt.mu.Lock()
	info, ok := rt.stacks[stackKey(org, proj, stack)]
	rt.mu.Unlock()
	if !ok {
		return 404, errBody("stack not found", org+"/"+proj+"/"+stack)
	}
	return 200, rt.stackBody(info)
}

func (rt *router) deleteStack(org, proj, stack string) (int, interface{}) {
	rt.mu.Lock()
	delete(rt.stacks, stackKey(org, proj, stack))
	rt.mu.Unlock()
	return 200, map[string]interface{}{}
}

func (rt *router) stackBody(s stackInfo) map[string]interface{} {
	return map[string]interface{}{
		"orgName":          s.orgName,
		"projectName":      s.projectName,
		"stackName":        s.stackName,
		"currentOperation": nil,
		"activeUpdate":     "",
		"tags":             s.tags,
	}
}

func (rt *router) exportStack() (int, interface{}) {
	return 200, map[string]interface{}{
		"version":    3,
		"deployment": nil,
	}
}

func (rt *router) createOp(kind string) (int, interface{}) {
	n := atomic.AddInt64(&opCounter, 1)
	updateID := fmt.Sprintf("%s-%04d", kind, n)
	token := fmt.Sprintf("lease-%04d", n)

	rt.mu.Lock()
	rt.ops[updateID] = opState{token: token}
	rt.mu.Unlock()

	return 200, map[string]interface{}{
		"updateID": updateID,
		"version":  1,
	}
}

func (rt *router) startOp(updateID string, body []byte) (int, interface{}) {
	rt.mu.Lock()
	state, ok := rt.ops[updateID]
	rt.mu.Unlock()

	token := state.token
	if !ok {
		// Unknown updateID — return a token anyway so the CLI can proceed.
		token = fmt.Sprintf("lease-fallback-%s", updateID)
	}

	// Echo back the maximum journal version the client asked for so the CLI uses the
	// POST .../journalentries path instead of falling back to PATCH .../checkpoint.
	// Per AppStartUpdateRequest/Response: 0 disables journaling; anything >=1 enables it.
	var req struct {
		JournalVersion int64 `json:"journalVersion"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck
	journalVersion := req.JournalVersion
	if journalVersion == 0 {
		journalVersion = 1
	}

	return 200, map[string]interface{}{
		"token":           token,
		"tokenExpiration": time.Now().Add(time.Hour).Unix(),
		"version":         1,
		"journalVersion":  journalVersion,
	}
}

func (rt *router) renewLease() (int, interface{}) {
	n := atomic.AddInt64(&opCounter, 1)
	return 200, map[string]interface{}{
		"token":    fmt.Sprintf("renewed-%04d", n),
		"duration": 3600,
	}
}

// Secrets stubs: identity passthrough. The service's "encrypt" wraps a plaintext
// value into an opaque ciphertext blob; the engine sends it back to "decrypt"
// later. For recording purposes we just echo the bytes — the wire shape is what
// we care about, not real encryption.
func (rt *router) encryptValue(body []byte) (int, interface{}) {
	var req struct {
		Plaintext string `json:"plaintext"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck
	return 200, map[string]interface{}{"ciphertext": req.Plaintext}
}

func (rt *router) decryptValue(body []byte) (int, interface{}) {
	var req struct {
		Ciphertext string `json:"ciphertext"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck
	return 200, map[string]interface{}{"plaintext": req.Ciphertext}
}

func (rt *router) batchDecrypt(body []byte) (int, interface{}) {
	var req struct {
		Ciphertexts []string `json:"ciphertexts"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck
	// Response shape: map of ciphertext → plaintext (best guess; revise if 4xx).
	out := make(map[string]string, len(req.Ciphertexts))
	for _, c := range req.Ciphertexts {
		out[c] = c
	}
	return 200, map[string]interface{}{"plaintexts": out}
}

func errBody(msg, detail string) map[string]interface{} {
	return map[string]interface{}{"message": msg, "detail": detail}
}
