import { describe, expect, test } from "bun:test";

import {
  buildRepositoryTree,
  collectCollapsibleKeys,
  computeDefaultCollapsedFolders,
  extractChangeCounts,
  filterDirectoryProjects,
  prioritizeInlineStacks,
  singleLeafProject,
} from "@/lib/directory-tree";
import type { ProjectDirectoryEntry, Workspace } from "@/lib/api";

describe("directory-tree", () => {
  test("groups projects by repo, compresses single-child folders, and puts non-repo projects last", () => {
    const projects: ProjectDirectoryEntry[] = [
      entry("vpc-core", { vcsOwner: "acme", vcsRepo: "platform-infra", vcsRoot: "infra/network", stackCount: 5, runningCount: 1 }),
      entry("eks-clusters", { vcsOwner: "acme", vcsRepo: "platform-infra", vcsRoot: "infra/compute", stackCount: 4, failedCount: 1 }),
      entry("billing-iac", { vcsOwner: "acme", vcsRepo: "platform-infra", vcsRoot: "services/billing/iac", stackCount: 6 }),
      entry("warehouse-core", { vcsOwner: "acme", vcsRepo: "data-warehouse", vcsRoot: null, stackCount: 3 }),
      entry("sandbox", { vcsOwner: null, vcsRepo: null, vcsRoot: null, stackCount: 2 }),
    ];

    const groups = buildRepositoryTree(projects);
    expect(groups.map((g) => (g.standalone ? "standalone" : `${g.vcsOwner}/${g.vcsRepo}`))).toEqual([
      "acme/data-warehouse",
      "acme/platform-infra",
      "standalone",
    ]);

    const warehouse = groups[0];
    expect(warehouse.rootProjects.map((p) => p.project)).toEqual(["warehouse-core"]);
    expect(warehouse.folders).toEqual([]);

    const platform = groups[1];
    expect(platform.stackCount).toBe(15);
    expect(platform.runningCount).toBe(1);
    expect(platform.failedCount).toBe(1);
    expect(platform.folders.map((f) => f.label)).toEqual(["infra", "services/billing/iac"]);
    expect(platform.folders[0].folders.map((f) => f.label)).toEqual(["compute", "network"]);
    expect(platform.folders[1].projects.map((p) => p.project)).toEqual(["billing-iac"]);

    const standalone = groups[2];
    expect(standalone.standalone).toBe(true);
    expect(standalone.rootProjects.map((p) => p.project)).toEqual(["sandbox"]);
  });

  test("filters by search term across repo, folder path, project, and status", () => {
    const projects = [
      entry("vpc-core", { vcsOwner: "acme", vcsRepo: "platform-infra", vcsRoot: "infra/network", runningCount: 1 }),
      entry("eks-clusters", { vcsOwner: "acme", vcsRepo: "platform-infra", vcsRoot: "infra/compute", failedCount: 1 }),
      entry("sandbox", {}),
    ];

    expect(filterDirectoryProjects(projects, "network", "all").map((p) => p.project)).toEqual(["vpc-core"]);
    expect(filterDirectoryProjects(projects, "platform-infra", "failed").map((p) => p.project)).toEqual(["eks-clusters"]);
    expect(filterDirectoryProjects(projects, "", "running").map((p) => p.project)).toEqual(["vpc-core"]);
  });

  test("extracts compact change counts from summaryEvent without double-counting replacements", () => {
    expect(extractChangeCounts({ resourceChanges: { create: 2, update: 1, same: 10 } })).toEqual({
      creates: 2,
      updates: 1,
      deletes: 0,
    });
    expect(
      extractChangeCounts({
        resourceChanges: { "create-replacement": 1, replace: 1, "delete-replaced": 1 },
      }),
    ).toEqual({
      creates: 1,
      updates: 0,
      deletes: 1,
    });
    expect(extractChangeCounts({ resourceChanges: { same: 5 } })).toBeNull();
    expect(extractChangeCounts(null)).toBeNull();
  });

  test("collapses single-project leaf folders and omits redundant matching path prefix", () => {
    const groups = buildRepositoryTree([
      entry("application-fleet", { vcsOwner: "acme", vcsRepo: "mono", vcsRoot: "projects/application-fleet" }),
      entry("billing-iac", { vcsOwner: "acme", vcsRepo: "mono", vcsRoot: "projects/billing/iac" }),
    ]);
    const projectsFolder = groups[0].folders[0];
    expect(singleLeafProject(projectsFolder)).toBeNull();

    const appLeaf = singleLeafProject(projectsFolder.folders[0]);
    expect(appLeaf?.project.project).toBe("application-fleet");
    expect(appLeaf?.pathPrefix).toBeNull();

    const billingLeaf = singleLeafProject(projectsFolder.folders[1]);
    expect(billingLeaf?.project.project).toBe("billing-iac");
    expect(billingLeaf?.pathPrefix).toBe("billing/iac/");
  });

  test("auto-collapses calm folders in large monorepos while keeping active/failed folders open", () => {
    const projects: ProjectDirectoryEntry[] = [];
    for (let i = 0; i < 14; i++) {
      const group = i < 7 ? "calm" : "active";
      projects.push(
        entry(`proj-${i}`, {
          vcsOwner: "acme",
          vcsRepo: "huge-mono",
          vcsRoot: `${group}/p-${i}`,
          failedCount: i === 10 ? 1 : 0,
        }),
      );
    }
    const groups = buildRepositoryTree(projects);
    const collapsed = computeDefaultCollapsedFolders(groups);
    expect(collapsed.has(`${groups[0].key}:calm`)).toBe(true);
    expect(collapsed.has(`${groups[0].key}:active`)).toBe(false);
    expect(collectCollapsibleKeys(groups).length).toBeGreaterThanOrEqual(3);
  });

  test("prioritizes running, then failed, then most recently updated inline stacks", () => {
    const ordered = prioritizeInlineStacks([
      stackRow("old-ok", { status: "succeeded", startedAt: "2026-08-01T08:00:00Z" }),
      stackRow("failed-one", { status: "failed", startedAt: "2026-08-01T09:00:00Z" }),
      stackRow("new-ok", { status: "succeeded", startedAt: "2026-08-01T11:00:00Z" }),
      stackRow("running-one", { status: "in-progress", startedAt: "2026-08-01T10:00:00Z" }),
    ]);
    expect(ordered.map((s) => s.stack)).toEqual([
      "running-one",
      "failed-one",
      "new-ok",
      "old-ok",
    ]);
  });
});

function stackRow(
  stack: string,
  lastUpdate: { status: string; startedAt: string },
): Workspace {
  return {
    id: stack,
    kind: "pulumi",
    org: "acme",
    project: "infra",
    projectCreatedAt: "2026-08-01T00:00:00Z",
    stack,
    version: 1,
    resourceCount: 5,
    lastUpdate: {
      id: `${stack}-u`,
      kind: "update",
      status: lastUpdate.status,
      startedAt: lastUpdate.startedAt,
      version: 1,
      actor: null,
    },
    lock: null,
  };
}

function entry(
  project: string,
  overrides: Partial<ProjectDirectoryEntry> = {},
): ProjectDirectoryEntry {
  return {
    org: "acme",
    project,
    projectCreatedAt: "2026-08-01T00:00:00Z",
    vcsKind: overrides.vcsOwner ? "github.com" : null,
    vcsOwner: null,
    vcsRepo: null,
    vcsRoot: null,
    stackCount: 1,
    resourceCount: 10,
    runningCount: 0,
    failedCount: 0,
    lastActivityAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}
