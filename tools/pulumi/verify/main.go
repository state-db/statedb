// verify checks a Pulumi deployment JSON against Pulumi's own snapshot
// integrity rules (snapshot.VerifyIntegrity). It accepts either a raw
// DeploymentV3 object or the `pulumi stack export` envelope
// {"version": 3, "deployment": {...}}. Envelope version 4 (v3 body plus a
// feature list, emitted for stacks using views/hooks/taint/replace-with)
// is accepted too.
//
// Exit codes: 0 integrity ok, 1 integrity violation (rule on stdout),
// 2 unusable input.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	"github.com/pulumi/pulumi/pkg/v3/resource/stack/snapshot"
	"github.com/pulumi/pulumi/sdk/v3/go/common/apitype"
)

func main() {
	raw, err := readInput()
	if err != nil {
		fmt.Fprintf(os.Stderr, "verify: %v\n", err)
		os.Exit(2)
	}
	message, code := check(raw)
	if code == 2 {
		fmt.Fprintln(os.Stderr, message)
	} else {
		fmt.Println(message)
	}
	os.Exit(code)
}

func check(raw []byte) (string, int) {
	deployment, err := unwrap(raw)
	if err != nil {
		return fmt.Sprintf("verify: %v", err), 2
	}

	var snap apitype.DeploymentV3
	if err := json.Unmarshal(deployment, &snap); err != nil {
		return fmt.Sprintf("verify: deployment does not parse as DeploymentV3: %v", err), 2
	}

	if err := snapshot.VerifyIntegrity(&snap); err != nil {
		return fmt.Sprintf("integrity: FAIL: %v", err), 1
	}
	return "integrity: ok", 0
}

func readInput() ([]byte, error) {
	if len(os.Args) > 2 {
		return nil, fmt.Errorf("usage: verify [deployment.json] (defaults to stdin)")
	}
	if len(os.Args) == 2 && os.Args[1] != "-" {
		data, err := os.ReadFile(os.Args[1])
		if err != nil {
			return nil, err
		}
		return data, nil
	}
	return io.ReadAll(os.Stdin)
}

// unwrap returns the DeploymentV3 body, unwrapping the stack-export envelope
// when present.
func unwrap(raw []byte) (json.RawMessage, error) {
	var envelope struct {
		Version    int             `json:"version"`
		Deployment json.RawMessage `json:"deployment"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, fmt.Errorf("input is not JSON: %w", err)
	}
	if envelope.Deployment == nil {
		return raw, nil
	}
	if envelope.Version != 3 && envelope.Version != 4 {
		return nil, fmt.Errorf("unsupported deployment envelope version %d (want 3 or 4)", envelope.Version)
	}
	return envelope.Deployment, nil
}
