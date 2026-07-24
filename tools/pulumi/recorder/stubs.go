package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
)

var opCounter int64

const stableTokenExpirationUnix = 4102444800 // 2100-01-01T00:00:00Z
const pulumiVersion = "v3.242.0"
const pulumiManifestMagic = "4ae265b5e36f43e09c7ff61ff9e23e82843496e0c1ad370c8f62bd2c5f0f4369"

type opState struct {
	token      string
	kind       string
	stackKey   string
	hasJournal bool
}

type stackInfo struct {
	orgName     string
	projectName string
	stackName   string
	tags        map[string]interface{}
	// Only ever set by seedActiveUpdate: the CLI reads activeUpdate for `cancel` alone, and
	// updates the CLI drives itself are already finished by the time it reads the stack again.
	activeUpdate    string
	resourceOrder   []string
	resources       map[string]map[string]interface{}
	secretsProvider map[string]interface{}
}

type router struct {
	mu     sync.Mutex
	ops    map[string]opState   // updateID → lease state
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
				return rt.exportStack(org, proj, stack)
			}
		case "import":
			if method == "POST" {
				return 200, map[string]interface{}{}
			}
		case "rename":
			if method == "POST" {
				return rt.renameStack(org, proj, stack, body)
			}
		case "updates":
			if method == "GET" {
				return 200, map[string]interface{}{"updates": []interface{}{}, "itemsPerPage": 10}
			}
		case "tags":
			if method == "PATCH" {
				return rt.replaceTags(org, proj, stack, body)
			}
		case "preview", "update", "destroy", "refresh":
			if method == "POST" {
				return rt.createOp(stackKey(org, proj, stack), op)
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
		case "cancel":
			return rt.cancelOp(updateID)
		case "complete":
			return rt.completeOp(updateID)
		case "renew_lease":
			return rt.renewLease()
		case "journalentries":
			return rt.recordJournalEntries(org, proj, stack, updateID, body)
		case "checkpoint", "checkpointdelta", "checkpointverbatim":
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

// The CLI PATCHes the full desired tag map; store it so later GETs reflect tag edits.
func (rt *router) replaceTags(org, proj, stack string, body []byte) (int, interface{}) {
	var tags map[string]interface{}
	json.Unmarshal(body, &tags) //nolint:errcheck
	if tags == nil {
		tags = map[string]interface{}{}
	}
	rt.mu.Lock()
	if info, ok := rt.stacks[stackKey(org, proj, stack)]; ok {
		info.tags = tags
		rt.stacks[stackKey(org, proj, stack)] = info
	}
	rt.mu.Unlock()
	return 200, map[string]interface{}{}
}

func (rt *router) createStack(org, proj string, body []byte) (int, interface{}) {
	var req map[string]interface{}
	json.Unmarshal(body, &req) //nolint:errcheck
	stackName, _ := req["stackName"].(string)
	tags, _ := req["tags"].(map[string]interface{})
	if tags == nil {
		tags = map[string]interface{}{}
	}
	info := stackInfo{
		orgName:     org,
		projectName: proj,
		stackName:   stackName,
		tags:        tags,
		resources:   map[string]map[string]interface{}{},
	}
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

// renameStack re-keys the stack record under its new name (and possibly new
// project/org). The CLI sends the fully-qualified target as "newName"
// (e.g. "org/project/staging"); we take the trailing segments that are present.
func (rt *router) renameStack(org, proj, stack string, body []byte) (int, interface{}) {
	var req struct {
		NewName string `json:"newName"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck
	newOrg, newProj, newStack := org, proj, stack
	if req.NewName != "" {
		segs := strings.Split(req.NewName, "/")
		switch len(segs) {
		case 1:
			newStack = segs[0]
		case 2:
			newProj, newStack = segs[0], segs[1]
		default:
			newOrg, newProj, newStack = segs[len(segs)-3], segs[len(segs)-2], segs[len(segs)-1]
		}
	}

	rt.mu.Lock()
	defer rt.mu.Unlock()
	info, ok := rt.stacks[stackKey(org, proj, stack)]
	if !ok {
		return 404, errBody("stack not found", stackKey(org, proj, stack))
	}
	delete(rt.stacks, stackKey(org, proj, stack))
	info.orgName, info.projectName, info.stackName = newOrg, newProj, newStack
	rt.stacks[stackKey(newOrg, newProj, newStack)] = info
	// The CLI ignores the rename response body (client.RenameStack passes a nil response).
	return 200, map[string]interface{}{}
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
		"activeUpdate":     s.activeUpdate,
		"tags":             s.tags,
	}
}

func (rt *router) exportStack(org, proj, stack string) (int, interface{}) {
	rt.mu.Lock()
	info, ok := rt.stacks[stackKey(org, proj, stack)]
	rt.mu.Unlock()
	// Empty stacks still export as a DeploymentV3-shaped object (matching state-db and Pulumi
	// cloud), never as a null deployment.
	resources := make([]interface{}, 0)
	var secretsProvider interface{}
	if ok {
		for _, urn := range info.resourceOrder {
			if res := info.resources[urn]; res != nil {
				resources = append(resources, res)
			}
		}
		secretsProvider = info.secretsProvider
	}
	deployment := map[string]interface{}{
		"manifest": map[string]interface{}{
			"time":    "1970-01-01T00:00:00Z",
			"magic":   pulumiManifestMagic,
			"version": pulumiVersion,
		},
		"resources": resources,
	}
	if secretsProvider != nil {
		deployment["secrets_providers"] = secretsProvider
	}
	return 200, map[string]interface{}{
		"version":    3,
		"deployment": deployment,
	}
}

func (rt *router) createOp(key, kind string) (int, interface{}) {
	n := atomic.AddInt64(&opCounter, 1)
	updateID := fmt.Sprintf("%s-%04d", kind, n)
	token := fmt.Sprintf("lease-%04d", n)

	rt.mu.Lock()
	rt.ops[updateID] = opState{token: token, kind: kind, stackKey: key}
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

	// Echo back the journal version the client asked for so the recorder reflects
	// protocol negotiation rather than forcing journaling on older clients.
	var req struct {
		JournalVersion int64 `json:"journalVersion"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck

	resp := map[string]interface{}{
		"token":           token,
		"tokenExpiration": stableTokenExpirationUnix,
		"version":         1,
	}
	if req.JournalVersion > 0 {
		resp["journalVersion"] = req.JournalVersion
	}
	return 200, resp
}

func (rt *router) completeOp(updateID string) (int, interface{}) {
	rt.mu.Lock()
	state, ok := rt.ops[updateID]
	if ok && state.kind == "destroy" && state.hasJournal {
		if info, exists := rt.stacks[state.stackKey]; exists {
			info.resourceOrder = nil
			info.resources = map[string]map[string]interface{}{}
			rt.stacks[state.stackKey] = info
		}
	}
	delete(rt.ops, updateID)
	rt.mu.Unlock()
	return 200, map[string]interface{}{}
}

func (rt *router) cancelOp(updateID string) (int, interface{}) {
	rt.mu.Lock()
	state, ok := rt.ops[updateID]
	if ok {
		if info, exists := rt.stacks[state.stackKey]; exists && info.activeUpdate == updateID {
			info.activeUpdate = ""
			rt.stacks[state.stackKey] = info
		}
	}
	delete(rt.ops, updateID)
	rt.mu.Unlock()
	return 204, nil
}

// Fabricates the in-flight update that `pulumi cancel` requires. Driven out of band from
// entrypoint.sh (POST /meta/active-update) rather than by a pulumi command, because every
// update the CLI starts runs to completion — it never leaves a lease behind to cancel.
func (rt *router) seedActiveUpdate(key string) (string, bool) {
	n := atomic.AddInt64(&opCounter, 1)
	updateID := fmt.Sprintf("update-%04d", n)

	rt.mu.Lock()
	defer rt.mu.Unlock()

	info, exists := rt.stacks[key]
	if !exists {
		return "", false
	}
	rt.ops[updateID] = opState{
		token:    fmt.Sprintf("lease-%04d", n),
		kind:     "update",
		stackKey: key,
	}
	info.activeUpdate = updateID
	rt.stacks[key] = info
	return updateID, true
}

func (rt *router) recordJournalEntries(org, proj, stack, updateID string, body []byte) (int, interface{}) {
	var req struct {
		Entries []map[string]interface{} `json:"entries"`
	}
	json.Unmarshal(body, &req) //nolint:errcheck

	key := stackKey(org, proj, stack)
	rt.mu.Lock()
	defer rt.mu.Unlock()

	op := rt.ops[updateID]
	op.hasJournal = true
	rt.ops[updateID] = op

	info, ok := rt.stacks[key]
	if !ok {
		return 200, map[string]interface{}{}
	}
	if info.resources == nil {
		info.resources = map[string]map[string]interface{}{}
	}

	for _, entry := range req.Entries {
		if provider, ok := entry["secretsProvider"].(map[string]interface{}); ok {
			info.secretsProvider = provider
		}

		state, ok := entry["state"].(map[string]interface{})
		if !ok {
			continue
		}
		urn, _ := state["urn"].(string)
		if urn == "" {
			continue
		}
		if shouldDeleteJournalResource(op.kind, entry) {
			delete(info.resources, urn)
			info.resourceOrder = removeURN(info.resourceOrder, urn)
			continue
		}
		if _, exists := info.resources[urn]; !exists {
			info.resourceOrder = append(info.resourceOrder, urn)
		}
		info.resources[urn] = state
	}

	rt.stacks[key] = info
	return 200, map[string]interface{}{}
}

func shouldDeleteJournalResource(kind string, entry map[string]interface{}) bool {
	if kind != "destroy" {
		return false
	}
	if removeNew, ok := entry["removeNew"].(float64); ok && removeNew > 0 {
		return true
	}
	if removeOld, ok := entry["removeOld"].(float64); ok && removeOld > 0 {
		return true
	}
	if op, ok := entry["operation"].(map[string]interface{}); ok {
		return op["type"] == "deleting" || op["type"] == "deleted"
	}
	return false
}

func removeURN(urns []string, target string) []string {
	for i, urn := range urns {
		if urn == target {
			return append(urns[:i], urns[i+1:]...)
		}
	}
	return urns
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
