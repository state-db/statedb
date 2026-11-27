import type { ProjectDirectoryEntry, Workspace } from "@/lib/api";

export type DirectoryStatusFilter = "all" | "running" | "failed";

export interface DirectoryFolderNode {
  path: string;
  label: string;
  folders: DirectoryFolderNode[];
  projects: ProjectDirectoryEntry[];
  stackCount: number;
  resourceCount: number;
  runningCount: number;
  failedCount: number;
}

export interface RepositoryGroup {
  key: string;
  standalone: boolean;
  vcsKind: string | null;
  vcsOwner: string | null;
  vcsRepo: string | null;
  rootProjects: ProjectDirectoryEntry[];
  folders: DirectoryFolderNode[];
  projectCount: number;
  stackCount: number;
  resourceCount: number;
  runningCount: number;
  failedCount: number;
}

export function filterDirectoryProjects(
  projects: ProjectDirectoryEntry[],
  search: string,
  status: DirectoryStatusFilter,
): ProjectDirectoryEntry[] {
  const term = search.trim().toLowerCase();
  return projects.filter((entry) => {
    if (status === "running" && entry.runningCount === 0) {
      return false;
    }
    if (status === "failed" && entry.failedCount === 0) {
      return false;
    }
    if (!term) {
      return true;
    }
    const repoFull =
      entry.vcsOwner && entry.vcsRepo ? `${entry.vcsOwner}/${entry.vcsRepo}` : "";
    return [
      entry.org,
      entry.project,
      entry.vcsKind ?? "",
      entry.vcsOwner ?? "",
      entry.vcsRepo ?? "",
      repoFull,
      entry.vcsRoot ?? "",
    ].some((value) => value.toLowerCase().includes(term));
  });
}

export function buildRepositoryTree(projects: ProjectDirectoryEntry[]): RepositoryGroup[] {
  const repoBuckets = new Map<string, ProjectDirectoryEntry[]>();
  const standaloneProjects: ProjectDirectoryEntry[] = [];

  for (const project of projects) {
    if (project.vcsOwner && project.vcsRepo) {
      const host = project.vcsKind?.toLowerCase() ?? "";
      const ownerRepo = `${project.vcsOwner.toLowerCase()}/${project.vcsRepo.toLowerCase()}`;
      const key =
        host && host !== "github.com" ? `${host}|${ownerRepo}` : ownerRepo;
      const bucket = repoBuckets.get(key) ?? [];
      bucket.push(project);
      repoBuckets.set(key, bucket);
    } else {
      standaloneProjects.push(project);
    }
  }

  const groups: RepositoryGroup[] = [];
  for (const [key, entries] of repoBuckets.entries()) {
    const first = entries.find((e) => e.vcsKind !== null) ?? entries[0];
    const { rootProjects, folders } = buildFolderHierarchy(entries);
    groups.push({
      key,
      standalone: false,
      vcsKind: first.vcsKind,
      vcsOwner: first.vcsOwner,
      vcsRepo: first.vcsRepo,
      rootProjects,
      folders,
      projectCount: entries.length,
      stackCount: sum(entries, (e) => e.stackCount),
      resourceCount: sum(entries, (e) => e.resourceCount),
      runningCount: sum(entries, (e) => e.runningCount),
      failedCount: sum(entries, (e) => e.failedCount),
    });
  }

  groups.sort((a, b) =>
    `${a.vcsOwner}/${a.vcsRepo}`.localeCompare(`${b.vcsOwner}/${b.vcsRepo}`),
  );

  if (standaloneProjects.length > 0) {
    standaloneProjects.sort(compareProjects);
    groups.push({
      key: "__standalone__",
      standalone: true,
      vcsKind: null,
      vcsOwner: null,
      vcsRepo: null,
      rootProjects: standaloneProjects,
      folders: [],
      projectCount: standaloneProjects.length,
      stackCount: sum(standaloneProjects, (e) => e.stackCount),
      resourceCount: sum(standaloneProjects, (e) => e.resourceCount),
      runningCount: sum(standaloneProjects, (e) => e.runningCount),
      failedCount: sum(standaloneProjects, (e) => e.failedCount),
    });
  }

  return groups;
}

interface MutableTrieNode {
  segment: string;
  path: string;
  children: Map<string, MutableTrieNode>;
  projects: ProjectDirectoryEntry[];
}

function buildFolderHierarchy(entries: ProjectDirectoryEntry[]): {
  rootProjects: ProjectDirectoryEntry[];
  folders: DirectoryFolderNode[];
} {
  const rootProjects: ProjectDirectoryEntry[] = [];
  const rootChildren = new Map<string, MutableTrieNode>();

  for (const entry of entries) {
    const segments = splitRoot(entry.vcsRoot);
    if (segments.length === 0) {
      rootProjects.push(entry);
      continue;
    }
    let currentMap = rootChildren;
    let currentNode: MutableTrieNode | null = null;
    let prefix = "";
    for (const segment of segments) {
      prefix = prefix ? `${prefix}/${segment}` : segment;
      let next = currentMap.get(segment);
      if (!next) {
        next = { segment, path: prefix, children: new Map(), projects: [] };
        currentMap.set(segment, next);
      }
      currentNode = next;
      currentMap = next.children;
    }
    currentNode!.projects.push(entry);
  }

  rootProjects.sort(compareProjects);
  const folders = Array.from(rootChildren.values())
    .map(compressTrieNode)
    .sort((a, b) => a.label.localeCompare(b.label));

  return { rootProjects, folders };
}

/** Compresses single-child intermediate directory chains that hold no direct projects. */
function compressTrieNode(node: MutableTrieNode): DirectoryFolderNode {
  let label = node.segment;
  let current = node;
  while (current.projects.length === 0 && current.children.size === 1) {
    const onlyChild = current.children.values().next().value!;
    label = `${label}/${onlyChild.segment}`;
    current = onlyChild;
  }

  const folders = Array.from(current.children.values())
    .map(compressTrieNode)
    .sort((a, b) => a.label.localeCompare(b.label));
  const projects = [...current.projects].sort(compareProjects);

  const stackCount =
    sum(projects, (p) => p.stackCount) + sum(folders, (f) => f.stackCount);
  const resourceCount =
    sum(projects, (p) => p.resourceCount) + sum(folders, (f) => f.resourceCount);
  const runningCount =
    sum(projects, (p) => p.runningCount) + sum(folders, (f) => f.runningCount);
  const failedCount =
    sum(projects, (p) => p.failedCount) + sum(folders, (f) => f.failedCount);

  return {
    path: current.path,
    label,
    folders,
    projects,
    stackCount,
    resourceCount,
    runningCount,
    failedCount,
  };
}

function splitRoot(root: string | null): string[] {
  if (!root) {
    return [];
  }
  return root
    .split("/")
    .map((part) => part.trim())
    .filter((part) => part.length > 0 && part !== ".");
}

function compareProjects(a: ProjectDirectoryEntry, b: ProjectDirectoryEntry): number {
  return a.project.localeCompare(b.project) || a.org.localeCompare(b.org);
}

function sum<T>(items: T[], selector: (item: T) => number): number {
  return items.reduce((total, item) => total + selector(item), 0);
}

export interface ChangeCounts {
  creates: number;
  updates: number;
  deletes: number;
}

export function extractChangeCounts(
  summary: { resourceChanges?: Record<string, number> } | null,
): ChangeCounts | null {
  const changes = summary?.resourceChanges;
  if (!changes) {
    return null;
  }
  const creates = (changes.create ?? 0) + (changes["create-replacement"] ?? 0);
  // Matches stack-detail.tsx: a replacement already counts in create-replacement and
  // delete-replaced, so the replace marker is not double-counted as an in-place update.
  const updates = changes.update ?? 0;
  const deletes = (changes.delete ?? 0) + (changes["delete-replaced"] ?? 0);
  if (creates === 0 && updates === 0 && deletes === 0) {
    return null;
  }
  return { creates, updates, deletes };
}

export const AUTO_COLLAPSE_PROJECT_THRESHOLD = 12;
export const INLINE_STACK_LIMIT = 12;

/**
 * When a leaf folder holds exactly one project, collapse the folder and project
 * into a single row so monorepos don't pay 2x vertical height.
 */
export function singleLeafProject(
  folder: DirectoryFolderNode,
): { project: ProjectDirectoryEntry; pathPrefix: string | null } | null {
  if (folder.folders.length !== 0 || folder.projects.length !== 1) {
    return null;
  }
  const project = folder.projects[0];
  const pathPrefix = folder.label === project.project ? null : `${folder.label}/`;
  return { project, pathPrefix };
}

/**
 * Computes default collapsed folder keys for large repos (>12 projects) while
 * keeping folders with running or failed stacks expanded so issues stay visible.
 */
export function computeDefaultCollapsedFolders(groups: RepositoryGroup[]): Set<string> {
  const collapsed = new Set<string>();
  for (const group of groups) {
    if (group.projectCount <= AUTO_COLLAPSE_PROJECT_THRESHOLD) {
      continue;
    }
    const visit = (folder: DirectoryFolderNode) => {
      if (singleLeafProject(folder) !== null) {
        return;
      }
      if (folder.runningCount === 0 && folder.failedCount === 0) {
        collapsed.add(`${group.key}:${folder.path}`);
      } else {
        for (const child of folder.folders) {
          visit(child);
        }
      }
    };
    for (const folder of group.folders) {
      visit(folder);
    }
  }
  return collapsed;
}

/** Collects every collapsible repo and non-leaf folder key for Expand/Collapse All. */
export function collectCollapsibleKeys(groups: RepositoryGroup[]): string[] {
  const keys: string[] = [];
  for (const group of groups) {
    keys.push(group.key);
    const visit = (folder: DirectoryFolderNode) => {
      if (singleLeafProject(folder) !== null) {
        return;
      }
      keys.push(`${group.key}:${folder.path}`);
      for (const child of folder.folders) {
        visit(child);
      }
    };
    for (const folder of group.folders) {
      visit(folder);
    }
  }
  return keys;
}

/** Sorts inline stacks with running first, failed second, then most recently updated. */
export function prioritizeInlineStacks(stacks: Workspace[]): Workspace[] {
  return [...stacks].sort((a, b) => {
    const rankDiff = stackUrgencyRank(a) - stackUrgencyRank(b);
    if (rankDiff !== 0) return rankDiff;
    const aTime = a.lastUpdate?.startedAt ?? "";
    const bTime = b.lastUpdate?.startedAt ?? "";
    if (aTime !== bTime) return bTime.localeCompare(aTime);
    return a.stack.localeCompare(b.stack);
  });
}

function stackUrgencyRank(row: Workspace): number {
  if (row.lock !== null || row.lastUpdate?.status === "in-progress") return 0;
  const failed =
    typeof row.failed === "boolean"
      ? row.failed
      : row.lastUpdate?.status === "failed" && row.lastUpdate.version !== null;
  if (failed) return 1;
  return 2;
}
