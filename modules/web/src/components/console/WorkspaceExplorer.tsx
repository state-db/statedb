import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  ArrowUpRight,
  ChevronDown,
  ChevronRight,
  ChevronsDownUp,
  ChevronsUpDown,
  Folder,
  FolderGit2,
  FolderOpen,
  FolderTree,
  FolderX,
  Layers,
  List,
  Search,
  Users,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import {
  hasMultipleKinds,
  Pagination,
  StacksTable,
  StacksTableSkeleton,
  useScopedPage,
} from "@/components/console/StacksTable";
import { KindBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import {
  api,
  NotFoundError,
  UnauthorizedError,
  type ProjectDirectoryEntry,
  type RecentUpdateRow,
  type Workspace,
  type WorkspaceOverview,
} from "@/lib/api";
import {
  buildRepositoryTree,
  collectCollapsibleKeys,
  computeDefaultCollapsedFolders,
  extractChangeCounts,
  filterDirectoryProjects,
  INLINE_STACK_LIMIT,
  prioritizeInlineStacks,
  singleLeafProject,
  type DirectoryFolderNode,
  type DirectoryStatusFilter,
  type RepositoryGroup,
} from "@/lib/directory-tree";
import { cn } from "@/lib/utils";

type HomeViewMode = "explorer" | "table";

const HOME_VIEW_STORAGE_KEY = "state-db-home-view";

function loadInitialViewMode(): HomeViewMode {
  if (typeof window === "undefined") return "explorer";
  try {
    const stored = window.localStorage.getItem(HOME_VIEW_STORAGE_KEY);
    return stored === "table" ? "table" : "explorer";
  } catch {
    return "explorer";
  }
}

const EMPTY_OVERVIEW: WorkspaceOverview = {
  stacks: 0,
  projects: 0,
  resources: 0,
  failed: 0,
  running: 0,
};

export function WorkspaceExplorer({
  org,
  scopedToOrg = false,
  showManageMembers = false,
  emptyState,
}: {
  org: string | null;
  scopedToOrg?: boolean;
  showManageMembers?: boolean;
  emptyState?: React.ReactNode;
}) {
  const queryClient = useQueryClient();
  const [viewMode, setViewModeState] = useState<HomeViewMode>(loadInitialViewMode);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [dirFilter, setDirFilter] = useState<DirectoryStatusFilter>("all");
  const [railFilter, setRailFilter] = useState<DirectoryStatusFilter>("all");
  const [includePreviews, setIncludePreviews] = useState(false);

  const setViewMode = (next: HomeViewMode) => {
    setViewModeState(next);
    if (typeof window !== "undefined") {
      try {
        window.localStorage.setItem(HOME_VIEW_STORAGE_KEY, next);
      } catch {
        // Ignore storage restrictions (private browsing / blocked site data).
      }
    }
  };

  const tableScopeKey = `${org ?? ""}|${dirFilter}|${debouncedSearch}`;
  const [tablePage, setTablePage] = useScopedPage(tableScopeKey);

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedSearch(search.trim()), 250);
    return () => window.clearTimeout(id);
  }, [search]);

  const directoryQuery = useQuery({
    queryKey: ["workspaces", "directory", org ?? null],
    queryFn: () => api.workspaces.directory(org ?? undefined),
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 2,
    refetchInterval: (result) => (result.state.data?.overview.running ? 15_000 : false),
  });

  // During a rolling deploy, a new browser bundle can hit an older backend replica
  // that returns 404 for /api/workspaces/directory. Fall back to the classic Table view.
  const directoryUnavailable = !scopedToOrg && directoryQuery.error instanceof NotFoundError;
  const effectiveViewMode: HomeViewMode = directoryUnavailable ? "table" : viewMode;

  const recentQuery = useQuery({
    queryKey: [
      "workspaces",
      "recent-updates",
      { org: org ?? null, includePreviews, status: railFilter },
    ],
    queryFn: () =>
      api.workspaces.recentUpdates({
        org: org ?? undefined,
        includePreviews,
        status: railFilter === "all" ? undefined : railFilter,
        limit: 25,
      }),
    enabled: effectiveViewMode === "explorer",
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 2,
    refetchInterval: (result) =>
      result.state.data?.running.length ? 3_000 : 15_000,
  });

  const tableListQuery = useQuery({
    queryKey: [
      "workspaces",
      { org: org ?? null, search: debouncedSearch, filter: dirFilter, page: tablePage },
    ],
    queryFn: () =>
      api.workspaces.list({
        org: org ?? undefined,
        search: debouncedSearch || undefined,
        status: dirFilter === "all" ? undefined : dirFilter,
        page: tablePage,
      }),
    enabled: effectiveViewMode === "table",
    placeholderData: keepPreviousData,
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 2,
    refetchInterval: (result) =>
      result.state.data?.items.some((row) => row.lastUpdate?.status === "in-progress")
        ? 3_000
        : false,
  });

  // When the Recent Updates rail observes new or finished runs while the directory is idle,
  // refresh the directory and affected expanded projects so counts and badges stay live.
  const activitySignature = useMemo(() => {
    if (!recentQuery.data) return null;
    const runningKey = recentQuery.data.running.map((r) => `${r.id}:${r.status}`).join(",");
    const latestRecent = recentQuery.data.recent[0];
    return `${runningKey}|${latestRecent ? `${latestRecent.id}:${latestRecent.status}` : ""}`;
  }, [recentQuery.data]);

  const lastSignatureRef = useRef<string | null>(null);
  useEffect(() => {
    if (activitySignature === null) return;
    if (lastSignatureRef.current !== null && lastSignatureRef.current !== activitySignature) {
      void queryClient.invalidateQueries({
        queryKey: ["workspaces", "directory", org ?? null],
      });
      const touchedProjects = new Set(
        [...(recentQuery.data?.running ?? []), ...(recentQuery.data?.recent.slice(0, 5) ?? [])].map(
          (r) => `${r.org}\0${r.project}`,
        ),
      );
      for (const pair of touchedProjects) {
        const [itemOrg, itemProject] = pair.split("\0");
        void queryClient.invalidateQueries({
          queryKey: ["workspaces", { org: itemOrg, project: itemProject }],
        });
      }
    }
    lastSignatureRef.current = activitySignature;
  }, [activitySignature, org, queryClient, recentQuery.data]);

  const stackSearchQuery = useQuery({
    queryKey: [
      "workspaces",
      "stack-search",
      { org: org ?? null, search: debouncedSearch, status: dirFilter },
    ],
    queryFn: () =>
      api.workspaces.list({
        org: org ?? undefined,
        search: debouncedSearch,
        status: dirFilter === "all" ? undefined : dirFilter,
        page: 1,
      }),
    enabled: viewMode === "explorer" && debouncedSearch.length >= 2,
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 1,
  });

  const unauthorized = useUnauthorizedRedirect(
    directoryQuery.error ?? recentQuery.error ?? tableListQuery.error,
  );

  const allProjects = directoryQuery.data?.projects ?? [];
  const overview = directoryQuery.data?.overview ?? EMPTY_OVERVIEW;

  const filteredProjects = useMemo(
    () => filterDirectoryProjects(allProjects, search, dirFilter),
    [allProjects, search, dirFilter],
  );

  const repoGroups = useMemo(
    () => buildRepositoryTree(filteredProjects),
    [filteredProjects],
  );

  const repoCount = useMemo(
    () =>
      new Set(
        allProjects
          .filter((p) => p.vcsOwner && p.vcsRepo)
          .map((p) => `${p.vcsKind ?? ""}|${p.vcsOwner}/${p.vcsRepo}`),
      ).size,
    [allProjects],
  );

  const showOrgPrefix = useMemo(
    () => !scopedToOrg && new Set(allProjects.map((p) => p.org)).size > 1,
    [scopedToOrg, allProjects],
  );

  if (unauthorized) {
    return null;
  }

  if (scopedToOrg && directoryQuery.error instanceof NotFoundError) {
    return (
      <EmptyState
        icon={FolderX}
        title="No such organization"
        description={`The organization ${org} does not exist or you cannot access it.`}
        action={
          <Button asChild variant="outline">
            <Link to="/console">Back to all stacks</Link>
          </Button>
        }
      />
    );
  }

  if (directoryQuery.error && !directoryUnavailable) {
    return (
      <ErrorAlert
        title="Could not load stacks"
        error={directoryQuery.error}
        onRetry={() => void directoryQuery.refetch()}
      />
    );
  }

  if (!directoryUnavailable && !directoryQuery.isLoading && overview.stacks === 0) {
    if (scopedToOrg) {
      return (
        <div className="flex flex-col gap-4">
          <ExplorerHeader
            title={org ?? "Stacks"}
            overview={overview}
            repoCount={0}
            org={org}
            showManageMembers={showManageMembers}
            filter={dirFilter}
            onFilterChange={setDirFilter}
            search={search}
            onSearchChange={setSearch}
            viewMode={viewMode}
            onViewModeChange={setViewMode}
          />
          <EmptyState
            icon={FolderX}
            title="No stacks in this organization"
            description="Stacks appear here after they are created with the CLI."
          />
        </div>
      );
    }
    return <>{emptyState}</>;
  }

  const tableResult = tableListQuery.data;
  const tableRows = tableResult?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <ExplorerHeader
        title={scopedToOrg && org ? org : "Stacks"}
        overview={overview}
        repoCount={repoCount}
        org={org}
        showManageMembers={showManageMembers}
        filter={dirFilter}
        onFilterChange={setDirFilter}
        search={search}
        onSearchChange={setSearch}
        viewMode={viewMode}
        onViewModeChange={setViewMode}
      />

      {effectiveViewMode === "table" ? (
        <div className="flex flex-col gap-4">
          {tableListQuery.error && !(tableListQuery.error instanceof NotFoundError) ? (
            <ErrorAlert
              title="Could not load stacks"
              error={tableListQuery.error}
              onRetry={() => void tableListQuery.refetch()}
            />
          ) : tableListQuery.isLoading ? (
            <StacksTableSkeleton />
          ) : tableRows.length === 0 ? (
            <EmptyState
              icon={Search}
              title="No stacks match"
              description="Try a different search or status filter."
            />
          ) : (
            <StacksTable rows={tableRows} grouped showKind={hasMultipleKinds(tableRows)} />
          )}
          {tableResult && tableResult.total > tableResult.pageSize && (
            <Pagination
              page={tableResult.page}
              pageSize={tableResult.pageSize}
              total={tableResult.total}
              onPageChange={setTablePage}
            />
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.55fr)_minmax(310px,1fr)] lg:items-start">
          {/* Left column: Split Explorer (Repos -> Folders -> Projects -> Lazy Stacks) */}
          <section className="flex min-w-0 flex-col gap-3" aria-label="Repository and project explorer">
            {debouncedSearch.length >= 2 && (
              <MatchingStacksCard
                query={debouncedSearch}
                items={stackSearchQuery.data?.items ?? []}
                total={stackSearchQuery.data?.total ?? 0}
                isLoading={stackSearchQuery.isLoading}
                showOrgPrefix={showOrgPrefix}
              />
            )}

            {directoryQuery.isLoading ? (
              <ExplorerTreeSkeleton />
            ) : repoGroups.length === 0 ? (
              debouncedSearch.length >= 2 &&
              (stackSearchQuery.isLoading || (stackSearchQuery.data?.items.length ?? 0) > 0) ? null : (
                <EmptyState
                  icon={Search}
                  title="No stacks or projects match"
                  description="Try a different search term or status filter."
                />
              )
            ) : (
              <ExplorerTree
                groups={repoGroups}
                showOrgPrefix={showOrgPrefix}
                isSearching={search.trim().length > 0}
              />
            )}
          </section>

          {/* Right column: Sticky Recent Updates Rail */}
          <aside className="min-w-0 lg:sticky lg:top-6" aria-label="Recent updates">
            <RecentUpdatesRail
              data={recentQuery.data}
              isLoading={recentQuery.isLoading}
              error={recentQuery.error}
              onRetry={() => void recentQuery.refetch()}
              filter={railFilter}
              onFilterChange={setRailFilter}
              includePreviews={includePreviews}
              onIncludePreviewsChange={setIncludePreviews}
              showOrgPrefix={showOrgPrefix}
            />
          </aside>
        </div>
      )}
    </div>
  );
}

function ExplorerHeader({
  title,
  overview,
  repoCount,
  org,
  showManageMembers,
  filter,
  onFilterChange,
  search,
  onSearchChange,
  viewMode,
  onViewModeChange,
}: {
  title: string;
  overview: WorkspaceOverview;
  repoCount: number;
  org: string | null;
  showManageMembers: boolean;
  filter: DirectoryStatusFilter;
  onFilterChange: (next: DirectoryStatusFilter) => void;
  search: string;
  onSearchChange: (next: string) => void;
  viewMode: HomeViewMode;
  onViewModeChange: (next: HomeViewMode) => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          <p className="text-sm text-muted-foreground">
            {repoCount > 0 && (
              <>
                <span className="font-mono font-medium text-foreground">{repoCount}</span>{" "}
                {pluralize("repo", repoCount)} ·{" "}
              </>
            )}
            <span className="font-mono font-medium text-foreground">{overview.projects}</span>{" "}
            {pluralize("project", overview.projects)} ·{" "}
            <span className="font-mono font-medium text-foreground">{overview.stacks}</span>{" "}
            {pluralize("stack", overview.stacks)} ·{" "}
            <span className="font-mono font-medium text-foreground">
              {overview.resources.toLocaleString()}
            </span>{" "}
            {pluralize("resource", overview.resources)}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <div
            role="group"
            aria-label="Layout view"
            className="inline-flex items-center rounded-md border bg-muted p-0.5"
          >
            <button
              type="button"
              aria-pressed={viewMode === "explorer"}
              onClick={() => onViewModeChange("explorer")}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-sm px-2.5 py-1 text-xs font-medium transition-colors",
                viewMode === "explorer"
                  ? "bg-card text-foreground shadow-xs"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <FolderTree className="size-3.5" />
              Explorer
            </button>
            <button
              type="button"
              aria-pressed={viewMode === "table"}
              onClick={() => onViewModeChange("table")}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-sm px-2.5 py-1 text-xs font-medium transition-colors",
                viewMode === "table"
                  ? "bg-card text-foreground shadow-xs"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <List className="size-3.5" />
              Table
            </button>
          </div>

          {showManageMembers && org && (
            <Button asChild variant="outline" size="sm">
              <Link to="/console/admin/orgs/$org" params={{ org }}>
                <Users data-icon="inline-start" />
                Manage members
              </Link>
            </Button>
          )}
        </div>
      </div>

      <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Explorer status filter">
          <StatusPill active={filter === "all"} tone="info" onClick={() => onFilterChange("all")}>
            All{" "}
            <span className="font-mono font-semibold">
              {viewMode === "table" ? overview.stacks : overview.projects}
            </span>
          </StatusPill>
          <StatusPill
            active={filter === "running"}
            tone="info"
            pulse={overview.running > 0}
            onClick={() => onFilterChange("running")}
          >
            Running <span className="font-mono font-semibold">{overview.running}</span>
          </StatusPill>
          <StatusPill
            active={filter === "failed"}
            tone="destructive"
            onClick={() => onFilterChange("failed")}
          >
            Failed <span className="font-mono font-semibold">{overview.failed}</span>
          </StatusPill>
        </div>

        <div className="relative w-full sm:max-w-xs">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder="Search repos, folders, projects, stacks..."
            className="h-9 pl-8"
          />
        </div>
      </div>
    </div>
  );
}

function StatusPill({
  active,
  tone,
  pulse,
  onClick,
  children,
}: {
  active: boolean;
  tone: "info" | "destructive";
  pulse?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors",
        active
          ? tone === "destructive"
            ? "border-transparent bg-destructive/15 text-destructive"
            : "border-transparent bg-info/15 text-info"
          : "border-border bg-card text-foreground hover:bg-muted",
      )}
    >
      {pulse && <span className="size-1.5 rounded-full bg-current animate-pulse" />}
      {children}
    </button>
  );
}

function MatchingStacksCard({
  query,
  items,
  total,
  isLoading,
  showOrgPrefix,
}: {
  query: string;
  items: Workspace[];
  total: number;
  isLoading: boolean;
  showOrgPrefix: boolean;
}) {
  if (!isLoading && items.length === 0) {
    return null;
  }

  const shown = prioritizeInlineStacks(items).slice(0, 10);

  return (
    <div className="overflow-hidden rounded-md border bg-card">
      <div className="flex items-center justify-between gap-2 border-b bg-muted/40 px-3 py-2">
        <span className="text-xs font-semibold tracking-tight">
          Matching stacks for &ldquo;{query}&rdquo;
        </span>
        {!isLoading && (
          <Badge variant="muted" className="font-mono">
            {total > shown.length ? `${shown.length} of ${total}` : total}
          </Badge>
        )}
      </div>
      {isLoading ? (
        <div className="space-y-1.5 p-3">
          <Skeleton className="h-4 w-52" />
          <Skeleton className="h-4 w-44" />
        </div>
      ) : (
        <div className="divide-y divide-border/50">
          {shown.map((row) => (
            <InlineStackRow
              key={row.id}
              row={row}
              paddingLeft={12}
              labelPrefix={showOrgPrefix ? `${row.org} / ${row.project} / ` : `${row.project} / `}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ExplorerTree({
  groups,
  showOrgPrefix,
  isSearching,
}: {
  groups: RepositoryGroup[];
  showOrgPrefix: boolean;
  isSearching: boolean;
}) {
  const [collapsedNodes, setCollapsedNodes] = useState<Set<string>>(() =>
    computeDefaultCollapsedFolders(groups),
  );
  const [expandedProjects, setExpandedProjects] = useState<Set<string>>(() => new Set());
  const initializedRef = useRef(false);

  useEffect(() => {
    if (!initializedRef.current && groups.length > 0) {
      initializedRef.current = true;
      setCollapsedNodes(computeDefaultCollapsedFolders(groups));
    }
  }, [groups]);

  const allCollapsibleKeys = useMemo(() => collectCollapsibleKeys(groups), [groups]);
  const allCollapsed =
    allCollapsibleKeys.length > 0 &&
    allCollapsibleKeys.every((key) => collapsedNodes.has(key));

  const effectiveCollapsed = isSearching ? new Set<string>() : collapsedNodes;

  const toggleCollapse = (key: string) => {
    setCollapsedNodes((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleProject = (key: string) => {
    setExpandedProjects((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  return (
    <div className="overflow-hidden rounded-md border bg-card">
      {!isSearching && allCollapsibleKeys.length > 1 && (
        <div className="flex items-center justify-end border-b bg-muted/25 px-2.5 py-1">
          <button
            type="button"
            onClick={() =>
              setCollapsedNodes(allCollapsed ? new Set() : new Set(allCollapsibleKeys))
            }
            className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            {allCollapsed ? (
              <>
                <ChevronsUpDown className="size-3" />
                Expand all
              </>
            ) : (
              <>
                <ChevronsDownUp className="size-3" />
                Collapse all
              </>
            )}
          </button>
        </div>
      )}

      {groups.map((group, groupIndex) => {
        const repoOpen = !effectiveCollapsed.has(group.key);
        return (
          <div
            key={group.key}
            className={cn(groupIndex > 0 && "border-t border-border")}
          >
            {/* Level 1: Repository or Standalone root */}
            <button
              type="button"
              onClick={() => toggleCollapse(group.key)}
              aria-expanded={repoOpen}
              className="flex w-full items-center justify-between gap-2 bg-muted/45 px-3 py-2 text-left transition-colors hover:bg-muted/75"
            >
              <span className="flex min-w-0 items-center gap-2">
                {repoOpen ? (
                  <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
                ) : (
                  <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                )}
                {group.standalone ? (
                  <Folder className="size-4 shrink-0 text-warning" />
                ) : (
                  <FolderGit2 className="size-4 shrink-0 text-muted-foreground" />
                )}
                <span className="truncate text-sm font-semibold">
                  {group.standalone ? (
                    <>
                      Standalone projects{" "}
                      <span className="font-normal text-muted-foreground">(no repository)</span>
                    </>
                  ) : (
                    <>
                      <span className="font-normal text-muted-foreground">{group.vcsOwner} / </span>
                      {group.vcsRepo}
                    </>
                  )}
                </span>
                {!group.standalone && group.vcsKind && group.vcsKind !== "github.com" && (
                  <Badge variant="outline" className="hidden font-mono text-[11px] sm:inline-flex">
                    {group.vcsKind}
                  </Badge>
                )}
              </span>

              <span className="flex shrink-0 items-center gap-1.5">
                {group.runningCount > 0 && (
                  <Badge variant="info" className="gap-1">
                    <span className="size-1.5 rounded-full bg-current animate-pulse" />
                    {group.runningCount} running
                  </Badge>
                )}
                {group.failedCount > 0 && (
                  <Badge variant="destructive">{group.failedCount} failed</Badge>
                )}
                <Badge variant="muted" className="font-mono">
                  {group.stackCount} {pluralize("stack", group.stackCount)}
                </Badge>
              </span>
            </button>

            {repoOpen && (
              <div className="divide-y divide-border/50">
                {group.rootProjects.map((project) => (
                  <ProjectTreeRow
                    key={`${project.org}/${project.project}`}
                    entry={project}
                    depth={1}
                    expanded={expandedProjects.has(`${project.org}/${project.project}`)}
                    onToggle={() => toggleProject(`${project.org}/${project.project}`)}
                    showOrgPrefix={showOrgPrefix}
                    isRepoRoot={!group.standalone}
                  />
                ))}
                {group.folders.map((folder) => (
                  <FolderTreeBranch
                    key={`${group.key}:${folder.path}`}
                    repoKey={group.key}
                    folder={folder}
                    depth={1}
                    collapsedNodes={effectiveCollapsed}
                    onToggleCollapse={toggleCollapse}
                    expandedProjects={expandedProjects}
                    onToggleProject={toggleProject}
                    showOrgPrefix={showOrgPrefix}
                  />
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function FolderTreeBranch({
  repoKey,
  folder,
  depth,
  collapsedNodes,
  onToggleCollapse,
  expandedProjects,
  onToggleProject,
  showOrgPrefix,
}: {
  repoKey: string;
  folder: DirectoryFolderNode;
  depth: number;
  collapsedNodes: Set<string>;
  onToggleCollapse: (key: string) => void;
  expandedProjects: Set<string>;
  onToggleProject: (key: string) => void;
  showOrgPrefix: boolean;
}) {
  const leaf = singleLeafProject(folder);
  if (leaf) {
    return (
      <ProjectTreeRow
        entry={leaf.project}
        depth={depth}
        expanded={expandedProjects.has(`${leaf.project.org}/${leaf.project.project}`)}
        onToggle={() => onToggleProject(`${leaf.project.org}/${leaf.project.project}`)}
        showOrgPrefix={showOrgPrefix}
        isRepoRoot={false}
        pathPrefix={leaf.pathPrefix}
      />
    );
  }

  const folderKey = `${repoKey}:${folder.path}`;
  const isOpen = !collapsedNodes.has(folderKey);
  const paddingLeft = 12 + depth * 18;

  return (
    <div>
      <button
        type="button"
        onClick={() => onToggleCollapse(folderKey)}
        aria-expanded={isOpen}
        style={{ paddingLeft }}
        className="flex w-full items-center justify-between gap-2 py-1.5 pr-3 text-left text-xs text-muted-foreground transition-colors hover:bg-accent/40 hover:text-foreground"
      >
        <span className="flex min-w-0 items-center gap-1.5 font-mono">
          {isOpen ? (
            <ChevronDown className="size-3.5 shrink-0" />
          ) : (
            <ChevronRight className="size-3.5 shrink-0" />
          )}
          {isOpen ? (
            <FolderOpen className="size-3.5 shrink-0 text-primary/80" />
          ) : (
            <Folder className="size-3.5 shrink-0 text-primary/80" />
          )}
          <span className="truncate">{folder.label}/</span>
        </span>
        <span className="flex shrink-0 items-center gap-1.5 font-mono text-[11px]">
          {folder.runningCount > 0 && (
            <span className="size-1.5 rounded-full bg-info animate-pulse" title={`${folder.runningCount} running`} />
          )}
          {folder.failedCount > 0 && (
            <span className="size-1.5 rounded-full bg-destructive" title={`${folder.failedCount} failed`} />
          )}
          <span>{folder.stackCount}</span>
        </span>
      </button>

      {isOpen && (
        <div className="divide-y divide-border/40">
          {folder.projects.map((project) => (
            <ProjectTreeRow
              key={`${project.org}/${project.project}`}
              entry={project}
              depth={depth + 1}
              expanded={expandedProjects.has(`${project.org}/${project.project}`)}
              onToggle={() => onToggleProject(`${project.org}/${project.project}`)}
              showOrgPrefix={showOrgPrefix}
              isRepoRoot={false}
            />
          ))}
          {folder.folders.map((child) => (
            <FolderTreeBranch
              key={`${repoKey}:${child.path}`}
              repoKey={repoKey}
              folder={child}
              depth={depth + 1}
              collapsedNodes={collapsedNodes}
              onToggleCollapse={onToggleCollapse}
              expandedProjects={expandedProjects}
              onToggleProject={onToggleProject}
              showOrgPrefix={showOrgPrefix}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ProjectTreeRow({
  entry,
  depth,
  expanded,
  onToggle,
  showOrgPrefix,
  isRepoRoot,
  pathPrefix,
}: {
  entry: ProjectDirectoryEntry;
  depth: number;
  expanded: boolean;
  onToggle: () => void;
  showOrgPrefix: boolean;
  isRepoRoot: boolean;
  pathPrefix?: string | null;
}) {
  const queryClient = useQueryClient();
  const paddingLeft = 12 + depth * 18;

  const prefetchProjectStacks = () => {
    void queryClient.prefetchQuery({
      queryKey: ["workspaces", { org: entry.org, project: entry.project }],
      queryFn: () => api.workspaces.list({ org: entry.org, project: entry.project }),
      staleTime: 15_000,
    });
  };

  return (
    <div>
      <div
        style={{ paddingLeft }}
        onMouseEnter={prefetchProjectStacks}
        className={cn(
          "group flex items-center justify-between gap-2 py-2 pr-3 transition-colors hover:bg-accent/50",
          expanded && "bg-primary/5",
        )}
      >
        <div className="flex min-w-0 flex-1 items-center gap-1.5">
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={expanded}
            aria-label={expanded ? `Collapse ${entry.project} stacks` : `Expand ${entry.project} stacks`}
            className="flex size-5 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            {expanded ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
          </button>

          <Layers className="size-3.5 shrink-0 text-primary" />

          <Link
            to="/console/$org/$project"
            params={{ org: entry.org, project: entry.project }}
            className="flex min-w-0 items-center gap-1.5 truncate text-sm font-medium hover:underline"
          >
            {showOrgPrefix && (
              <span className="shrink-0 font-normal text-muted-foreground">{entry.org} /</span>
            )}
            {pathPrefix && (
              <span className="shrink-0 font-mono text-xs font-normal text-muted-foreground">
                {pathPrefix}
              </span>
            )}
            <span className="truncate">{entry.project}</span>
            {isRepoRoot && (
              <span className="shrink-0 font-mono text-[11px] font-normal text-muted-foreground">
                /
              </span>
            )}
          </Link>
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          {entry.runningCount > 0 && (
            <Badge variant="info" className="gap-1">
              <span className="size-1.5 rounded-full bg-current animate-pulse" />
              {entry.runningCount}
            </Badge>
          )}
          {entry.failedCount > 0 && (
            <Badge variant="destructive">{entry.failedCount} failed</Badge>
          )}
          <button
            type="button"
            onClick={onToggle}
            className="rounded-full bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
            title="Expand stacks inline"
          >
            {entry.stackCount} {pluralize("stack", entry.stackCount)}
          </button>
          <span className="hidden w-16 text-right font-mono text-xs text-muted-foreground sm:inline">
            {entry.resourceCount.toLocaleString()} res
          </span>
          <Link
            to="/console/$org/$project"
            params={{ org: entry.org, project: entry.project }}
            aria-label={`Open ${entry.project}`}
            className="flex size-6 items-center justify-center rounded text-muted-foreground opacity-60 transition-opacity hover:bg-muted hover:text-foreground group-hover:opacity-100"
          >
            <ArrowUpRight className="size-3.5" />
          </Link>
        </div>
      </div>

      {expanded && (
        <InlineProjectStacks
          org={entry.org}
          project={entry.project}
          paddingLeft={paddingLeft + 22}
        />
      )}
    </div>
  );
}

function InlineProjectStacks({
  org,
  project,
  paddingLeft,
}: {
  org: string;
  project: string;
  paddingLeft: number;
}) {
  const query = useQuery({
    queryKey: ["workspaces", { org, project }],
    queryFn: () => api.workspaces.list({ org, project }),
    refetchInterval: (result) =>
      result.state.data?.items.some(
        (row) => row.lock !== null || row.lastUpdate?.status === "in-progress",
      )
        ? 5_000
        : false,
  });

  if (query.isLoading) {
    return (
      <div className="space-y-1.5 bg-muted/30 py-2 pr-3" style={{ paddingLeft }}>
        <Skeleton className="h-5 w-48" />
        <Skeleton className="h-5 w-40" />
      </div>
    );
  }

  if (query.error) {
    return (
      <div className="bg-muted/30 py-2 pr-3 text-xs text-destructive" style={{ paddingLeft }}>
        Could not load stacks for {project}.
      </div>
    );
  }

  const allStacks = prioritizeInlineStacks(query.data?.items ?? []);
  const shown = allStacks.slice(0, INLINE_STACK_LIMIT);
  const remaining = allStacks.length - shown.length;

  return (
    <div className="divide-y divide-border/40 border-t border-border/40 bg-muted/30">
      {shown.map((row) => (
        <InlineStackRow key={row.id} row={row} paddingLeft={paddingLeft} />
      ))}
      {remaining > 0 && (
        <Link
          to="/console/$org/$project"
          params={{ org, project }}
          style={{ paddingLeft }}
          className="flex items-center gap-1 py-1.5 pr-3 text-xs font-medium text-primary transition-colors hover:bg-accent/55 hover:underline"
        >
          View all {allStacks.length} stacks
          <ArrowUpRight className="size-3.5" />
        </Link>
      )}
    </div>
  );
}

function InlineStackRow({
  row,
  paddingLeft,
  labelPrefix,
}: {
  row: Workspace;
  paddingLeft: number;
  labelPrefix?: string;
}) {
  const running = row.lock !== null || row.lastUpdate?.status === "in-progress";
  const isApplied = row.lastUpdate?.version !== null;
  const failed =
    !running &&
    (typeof row.failed === "boolean"
      ? row.failed
      : isApplied && row.lastUpdate?.status === "failed");
  const succeeded = !running && !failed && isApplied && row.lastUpdate?.status === "succeeded";
  const statusLabel = running
    ? "running"
    : failed
      ? "failed"
      : succeeded
        ? "succeeded"
        : "idle";

  return (
    <Link
      to="/console/$org/$project/$stack"
      params={{ org: row.org, project: row.project, stack: row.stack }}
      style={{ paddingLeft }}
      className="flex items-center justify-between gap-2 py-1.5 pr-3 text-xs transition-colors hover:bg-accent/55"
    >
      <span className="flex min-w-0 items-center gap-2">
        <span
          aria-hidden="true"
          title={statusLabel}
          className={cn(
            "size-2 shrink-0 rounded-full",
            running
              ? "bg-info animate-pulse"
              : failed
                ? "bg-destructive"
                : succeeded
                  ? "bg-success"
                  : "bg-muted-foreground/40",
          )}
        />
        <span className="sr-only">Status: {statusLabel}.</span>
        <span className="truncate font-medium text-foreground">
          {labelPrefix && (
            <span className="font-normal text-muted-foreground">{labelPrefix}</span>
          )}
          {row.stack}
        </span>
        {(running || failed) && (
          <Badge variant={running ? "info" : "destructive"} className="h-4 px-1.5 text-[10px]">
            {statusLabel}
          </Badge>
        )}
        {row.lastUpdate && (
          <span className="hidden items-center gap-1 text-muted-foreground sm:inline-flex">
            <span>{row.lastUpdate.kind}</span>
            {row.lastUpdate.version !== null && (
              <span className="font-mono">v{row.lastUpdate.version}</span>
            )}
          </span>
        )}
      </span>

      <span className="flex shrink-0 items-center gap-3 text-muted-foreground">
        {row.lastUpdate && <Timestamp value={row.lastUpdate.startedAt} />}
        <span className="w-16 text-right font-mono">
          {row.resourceCount ?? 0} res
        </span>
      </span>
    </Link>
  );
}

function RecentUpdatesRail({
  data,
  isLoading,
  error,
  onRetry,
  filter,
  onFilterChange,
  includePreviews,
  onIncludePreviewsChange,
  showOrgPrefix,
}: {
  data: { running: RecentUpdateRow[]; recent: RecentUpdateRow[] } | undefined;
  isLoading: boolean;
  error: unknown;
  onRetry: () => void;
  filter: DirectoryStatusFilter;
  onFilterChange: (next: DirectoryStatusFilter) => void;
  includePreviews: boolean;
  onIncludePreviewsChange: (next: boolean) => void;
  showOrgPrefix: boolean;
}) {
  const running = data?.running ?? [];
  const recent = data?.recent ?? [];

  return (
    <div className="overflow-hidden rounded-md border bg-card">
      <div className="flex flex-col gap-2 border-b bg-muted/40 px-3 py-2.5">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold tracking-tight">Recent Updates</h2>
          <div className="flex items-center gap-1" role="group" aria-label="Recent updates filter">
            {(["all", "running", "failed"] as const).map((option) => (
              <button
                key={option}
                type="button"
                aria-pressed={filter === option}
                onClick={() => onFilterChange(option)}
                className={cn(
                  "rounded-full border px-2 py-0.5 text-[11px] font-medium capitalize transition-colors",
                  filter === option
                    ? "border-primary bg-primary text-primary-foreground"
                    : "border-border bg-card text-muted-foreground hover:text-foreground",
                )}
              >
                {option}
              </button>
            ))}
          </div>
        </div>

        <label className="flex cursor-pointer items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>Show previews</span>
          <Switch
            checked={includePreviews}
            onCheckedChange={onIncludePreviewsChange}
            aria-label="Show previews"
          />
        </label>
      </div>

      {isLoading ? (
        <div className="divide-y divide-border/50">
          {Array.from({ length: 6 }).map((_, index) => (
            <div key={index} className="space-y-1.5 px-3 py-2.5">
              <Skeleton className="h-4 w-44" />
              <Skeleton className="h-3.5 w-32" />
            </div>
          ))}
        </div>
      ) : error ? (
        <div className="p-3">
          <ErrorAlert title="Could not load updates" error={error} onRetry={onRetry} />
        </div>
      ) : running.length === 0 && recent.length === 0 ? (
        <div className="px-3 py-8 text-center text-xs text-muted-foreground">
          {filter === "running"
            ? "No updates running right now."
            : filter === "failed"
              ? "No recent failed updates."
              : includePreviews
                ? "No recent updates yet."
                : "No recent applied updates. Toggle Show previews to include dry runs."}
        </div>
      ) : (
        <div>
          {running.length > 0 && (
            <div className="border-b border-info/25 bg-info/8">
              <div className="flex items-center gap-1.5 px-3 pt-2 pb-1 text-[10px] font-semibold uppercase tracking-wider text-info">
                <span className="size-1.5 rounded-full bg-info animate-pulse" />
                Running now · {running.length}
              </div>
              <div className="divide-y divide-info/15">
                {running.map((item) => (
                  <RecentUpdateItemRow
                    key={item.id}
                    item={item}
                    showOrgPrefix={showOrgPrefix}
                  />
                ))}
              </div>
            </div>
          )}

          {recent.length > 0 && (
            <div className="divide-y divide-border/50">
              {recent.map((item) => (
                <RecentUpdateItemRow
                  key={item.id}
                  item={item}
                  showOrgPrefix={showOrgPrefix}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function RecentUpdateItemRow({
  item,
  showOrgPrefix,
}: {
  item: RecentUpdateRow;
  showOrgPrefix: boolean;
}) {
  const isRunning = item.status === "in-progress";
  const isFailed = item.status === "failed";
  const isSucceeded = item.status === "succeeded";
  const statusLabel = isRunning ? "running" : item.status;
  const actorLabel = item.actor?.displayName ?? item.actor?.email ?? item.actor?.fallback ?? null;
  const counts = extractChangeCounts(item.summary);

  const content = (
    <div className="flex flex-col gap-1 px-3 py-2 transition-colors hover:bg-accent/50">
      <div className="flex items-center justify-between gap-2">
        <span className="flex min-w-0 items-center gap-2 text-xs">
          <span
            aria-hidden="true"
            title={statusLabel}
            className={cn(
              "size-2 shrink-0 rounded-full",
              isRunning
                ? "bg-info animate-pulse"
                : isFailed
                  ? "bg-destructive"
                  : isSucceeded
                    ? "bg-success"
                    : "bg-muted-foreground/50",
            )}
          />
          <span className="sr-only">Status: {statusLabel}.</span>
          <span className="truncate font-medium text-foreground">
            {showOrgPrefix && (
              <span className="font-normal text-muted-foreground">{item.org} / </span>
            )}
            <span className="font-normal text-muted-foreground">{item.project} / </span>
            {item.stack}
          </span>
        </span>
        <span className="shrink-0 text-[11px] text-muted-foreground">
          <Timestamp value={item.startedAt} />
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-1.5 pl-4 text-[11px] text-muted-foreground">
        <KindBadge kind={item.kind} />
        {(isFailed || item.status === "cancelled") && (
          <Badge variant={isFailed ? "destructive" : "muted"} className="h-4 px-1.5 text-[10px]">
            {statusLabel}
          </Badge>
        )}
        {item.version !== null && <span className="font-mono">v{item.version}</span>}
        {item.prNumber !== null && (
          <Badge variant="outline" className="h-4 px-1.5 font-mono text-[10px]">
            PR #{item.prNumber}
          </Badge>
        )}
        {counts && (
          <span className="inline-flex items-center gap-1 font-mono text-[11px]">
            {counts.creates > 0 && <span className="text-success">+{counts.creates}</span>}
            {counts.updates > 0 && <span className="text-warning">~{counts.updates}</span>}
            {counts.deletes > 0 && <span className="text-destructive">-{counts.deletes}</span>}
          </span>
        )}
        {actorLabel && <span className="truncate">· {actorLabel}</span>}
      </div>
    </div>
  );

  if (!item.dryRun && item.version !== null) {
    return (
      <Link
        to="/console/$org/$project/$stack/updates/$version"
        params={{
          org: item.org,
          project: item.project,
          stack: item.stack,
          version: String(item.version),
        }}
        className="block"
      >
        {content}
      </Link>
    );
  }

  return (
    <Link
      to="/console/$org/$project/$stack/previews/$updateId"
      params={{
        org: item.org,
        project: item.project,
        stack: item.stack,
        updateId: item.id,
      }}
      className="block"
    >
      {content}
    </Link>
  );
}

function ExplorerTreeSkeleton() {
  return (
    <div className="divide-y rounded-md border bg-card">
      {Array.from({ length: 4 }).map((_, index) => (
        <div key={index} className="space-y-2 p-3">
          <Skeleton className="h-5 w-52" />
          <div className="space-y-1.5 pl-5">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-56" />
          </div>
        </div>
      ))}
    </div>
  );
}

function pluralize(noun: string, count: number) {
  return count === 1 ? noun : `${noun}s`;
}
