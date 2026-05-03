package main

import (
	"fmt"
	"io"
	"net/http"
)

const (
	rawGitHubBase = "https://raw.githubusercontent.com"
	pulumiOwner   = "pulumi"
	pulumiRepo    = "pulumi"
)

func fetchFileAtRef(owner, repo, filePath, ref string) ([]byte, error) {
	url := fmt.Sprintf("%s/%s/%s/%s/%s", rawGitHubBase, owner, repo, ref, filePath)
	resp, err := http.Get(url) //nolint:gosec
	if err != nil {
		return nil, fmt.Errorf("fetching %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d fetching %s", resp.StatusCode, url)
	}
	return io.ReadAll(resp.Body)
}
