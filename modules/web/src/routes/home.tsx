import { Link } from "@tanstack/react-router";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Copy, ExternalLink, Search, Terminal } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { KindBadge, StatusBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { useShell } from "@/components/shell/AppShell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { cn } from "@/lib/utils";
import { api, NotFoundError, type Workspace, type WorkspaceOverview } from "@/lib/api";

type StackFilter = "all" | "running" | "failed";

const EMPTY_OVERVIEW: WorkspaceOverview = {
  stacks: 0,
  projects: 0,
  resources: 0,
  failed: 0,
  running: 0,
};

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

export function useScopedPage(scopeKey: string) {
  const [state, setState] = useState({ scopeKey, page: 1 });
  const page = state.scopeKey === scopeKey ? state.page : 1;
  const setPage = (nextPage: number) => setState({ scopeKey, page: nextPage });
  return [page, setPage] as const;
}

export function HomePage() {
  const { selectedOrg } = useShell();
  const [searchInput, setSearchInput] = useState("");
  const search = useDebouncedValue(searchInput, 300);
  const [filter, setFilter] = useState<StackFilter>("all");
  const scopeKey = `${selectedOrg ?? ""}|${filter}|${search}`;
  const [page, setPage] = useScopedPage(scopeKey);

  const listQuery = useQuery({
    queryKey: ["workspaces", { org: selectedOrg, search, filter, page }],
    queryFn: () =>
      api.workspaces.list({
        org: selectedOrg ?? undefined,
        search: search || undefined,
        status: filter === "all" ? undefined : filter,
        page,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: (result) =>
      result.state.data?.items.some((row) => row.lastUpdate?.status === "in-progress")
        ? 3_000
        : false,
  });

  const summaryQuery = useQuery({
    queryKey: ["workspaces", "summary", selectedOrg],
    queryFn: () => api.workspaces.summary(selectedOrg ?? undefined),
    refetchInterval: (result) => (result.state.data?.running ? 5_000 : 15_000),
  });

  const unauthorized = useUnauthorizedRedirect(listQuery.error ?? summaryQuery.error);

  if (unauthorized) {
    return null;
  }

  if (listQuery.isLoading || summaryQuery.isLoading) {
    return (
      <div className="flex flex-col gap-4">
        <StacksHeader
          overview={EMPTY_OVERVIEW}
          search={searchInput}
          onSearchChange={setSearchInput}
          filter={filter}
          onFilterChange={setFilter}
        />
        <StacksTableSkeleton />
      </div>
    );
  }

  if (summaryQuery.error) {
    return (
      <ErrorAlert
        title="Could not load stacks"
        error={summaryQuery.error}
        onRetry={() => void summaryQuery.refetch()}
      />
    );
  }

  const overview = summaryQuery.data ?? EMPTY_OVERVIEW;

  if (overview.stacks === 0) {
    return <FirstRunOnboarding />;
  }

  if (listQuery.error && !(listQuery.error instanceof NotFoundError)) {
    return (
      <ErrorAlert
        title="Could not load stacks"
        error={listQuery.error}
        onRetry={() => void listQuery.refetch()}
      />
    );
  }

  const result = listQuery.data;
  const rows = result?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <StacksHeader
        overview={overview}
        search={searchInput}
        onSearchChange={setSearchInput}
        filter={filter}
        onFilterChange={setFilter}
      />
      {rows.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No stacks match"
          description="Try a different search or status filter."
        />
      ) : (
        <StacksTable rows={rows} grouped showKind={hasMultipleKinds(rows)} />
      )}
      {result && result.total > result.pageSize && (
        <Pagination page={result.page} pageSize={result.pageSize} total={result.total} onPageChange={setPage} />
      )}
    </div>
  );
}

export function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
}) {
  const start = (page - 1) * pageSize + 1;
  const end = Math.min(page * pageSize, total);
  const lastPage = Math.ceil(total / pageSize);
  return (
    <div className="flex items-center justify-between gap-3">
      <p className="text-sm text-muted-foreground">
        {start}–{end} of {total.toLocaleString()}
      </p>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft data-icon="inline-start" />
          Previous
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page >= lastPage}
          onClick={() => onPageChange(page + 1)}
        >
          Next
          <ChevronRight data-icon="inline-end" />
        </Button>
      </div>
    </div>
  );
}

function StacksHeader({
  overview,
  search,
  onSearchChange,
  filter,
  onFilterChange,
}: {
  overview: WorkspaceOverview;
  search: string;
  onSearchChange: (value: string) => void;
  filter: StackFilter;
  onFilterChange: (value: StackFilter) => void;
}) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Stacks</h1>
        <p className="text-sm text-muted-foreground">
          {overview.projects} {pluralize("project", overview.projects)} · {overview.stacks}{" "}
          {pluralize("stack", overview.stacks)} · {overview.resources.toLocaleString()}{" "}
          {pluralize("resource", overview.resources)}
        </p>
      </div>

      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <FilterChips overview={overview} filter={filter} onFilterChange={onFilterChange} />
        <div className="relative w-full lg:max-w-sm">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder="Search stacks..."
            className="pl-8"
          />
        </div>
      </div>
    </div>
  );
}

function FilterChips({
  overview,
  filter,
  onFilterChange,
}: {
  overview: WorkspaceOverview;
  filter: StackFilter;
  onFilterChange: (value: StackFilter) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label="Stack status filter">
      <FilterChip
        active={filter === "all"}
        tone="info"
        onClick={() => onFilterChange("all")}
      >
        All <span className="font-mono font-semibold">{overview.stacks}</span>
      </FilterChip>
      <FilterChip
        active={filter === "running"}
        tone="info"
        pulse={overview.running > 0}
        onClick={() => onFilterChange("running")}
      >
        Running <span className="font-mono font-semibold">{overview.running}</span>
      </FilterChip>
      <FilterChip
        active={filter === "failed"}
        tone="destructive"
        onClick={() => onFilterChange("failed")}
      >
        Failed <span className="font-mono font-semibold">{overview.failed}</span>
      </FilterChip>
    </div>
  );
}

function FilterChip({
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
        "inline-flex items-center gap-2 rounded-full border px-3.5 py-1.5 text-sm font-medium transition-colors",
        active
          ? tone === "destructive"
            ? "border-transparent bg-destructive/10 text-destructive"
            : "border-transparent bg-info/10 text-info"
          : "border-border bg-card text-foreground hover:bg-muted",
      )}
    >
      {pulse && <span className="size-1.5 rounded-full bg-current animate-pulse" />}
      {children}
    </button>
  );
}

export function StacksTable({
  rows,
  grouped,
  showKind,
}: {
  rows: Workspace[];
  grouped: boolean;
  showKind: boolean;
}) {
  const projectGroups = groupByProject(rows);
  return (
    <>
      <div className="flex flex-col gap-3 md:hidden">
        {grouped
          ? projectGroups.map((group) => (
              <MobileProjectGroup
                key={`${group.org}/${group.project}`}
                group={group}
                showKind={showKind}
              />
            ))
          : rows.map((row) => (
              <MobileStackCard key={row.id} row={row} showKind={showKind} />
            ))}
      </div>
      <div className="hidden rounded-md border bg-card md:block">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[34%]">Stack</TableHead>
              <TableHead>Last update</TableHead>
              <TableHead className="w-28 text-right">Resources</TableHead>
              <TableHead className="w-32">Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {grouped
              ? projectGroups.map((group) => (
                  <ProjectRows key={`${group.org}/${group.project}`} group={group} showKind={showKind} />
                ))
              : rows.map((row) => <StackRow key={row.id} row={row} showKind={showKind} />)}
          </TableBody>
        </Table>
      </div>
    </>
  );
}

function MobileProjectGroup({
  group,
  showKind,
}: {
  group: ProjectGroup;
  showKind: boolean;
}) {
  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2 px-1">
        <div className="min-w-0 font-medium">
          <span className="text-muted-foreground">{group.org}</span>
          <span className="px-2 text-muted-foreground">/</span>
          {group.project}
        </div>
        <ProjectGroupSummary rows={group.rows} />
      </div>
      <div className="flex flex-col gap-2">
        {group.rows.map((row) => (
          <MobileStackCard key={row.id} row={row} showKind={showKind} />
        ))}
      </div>
    </section>
  );
}

function MobileStackCard({ row, showKind }: { row: Workspace; showKind: boolean }) {
  return (
    <Card className="rounded-lg shadow-none">
      <CardHeader className="flex flex-row items-start justify-between gap-3 pb-2">
        <div className="min-w-0">
          <CardTitle className="truncate text-base">
            <Link
              to="/console/$org/$project/$stack"
              params={{ org: row.org, project: row.project, stack: row.stack }}
              className="hover:underline"
            >
              {row.stack}
            </Link>
          </CardTitle>
          <CardDescription className="truncate">
            {row.org} / {row.project}
          </CardDescription>
        </div>
        <div className="shrink-0 text-right font-mono text-sm">
          {row.resourceCount ?? <span className="text-muted-foreground">-</span>}
        </div>
      </CardHeader>
      <CardContent className="flex flex-wrap items-center gap-2">
        {showKind && <Badge variant="outline" className="font-mono">{row.kind}</Badge>}
        {row.lastUpdate ? (
          <>
            <StatusBadge status={row.lastUpdate.status} />
            <KindBadge kind={row.lastUpdate.kind} />
            <Timestamp value={row.lastUpdate.startedAt} />
            <span className="font-mono text-xs text-muted-foreground">
              v{row.lastUpdate.version}
            </span>
          </>
        ) : (
          <span className="text-sm text-muted-foreground">No updates</span>
        )}
        {isActiveUpdate(row) ? <RunningIndicator row={row} /> : <Badge variant="muted">idle</Badge>}
      </CardContent>
    </Card>
  );
}

function ProjectRows({
  group,
  showKind,
}: {
  group: ProjectGroup;
  showKind: boolean;
}) {
  return (
    <>
      <TableRow className="bg-muted/40 hover:bg-muted/40">
        <TableCell colSpan={4} className="py-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="min-w-0 font-medium">
              <span className="text-muted-foreground">{group.org}</span>
              <span className="px-2 text-muted-foreground">/</span>
              {group.project}
            </div>
            <ProjectGroupSummary rows={group.rows} />
          </div>
        </TableCell>
      </TableRow>
      {group.rows.map((row) => (
        <StackRow key={row.id} row={row} showKind={showKind} />
      ))}
    </>
  );
}

function ProjectGroupSummary({ rows }: { rows: Workspace[] }) {
  const summary = summarizeStacks(rows);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge variant="muted">
        {summary.stacks} {pluralize("stack", summary.stacks)}
      </Badge>
      <Badge variant="muted">{summary.resources.toLocaleString()} resources</Badge>
      {summary.failed > 0 && (
        <Badge variant="warning">{summary.failed} failed</Badge>
      )}
    </div>
  );
}

function StackRow({ row, showKind }: { row: Workspace; showKind: boolean }) {
  return (
    <TableRow>
      <TableCell>
        <div className="flex min-w-0 items-center gap-2">
          <Link
            to="/console/$org/$project/$stack"
            params={{ org: row.org, project: row.project, stack: row.stack }}
            className="truncate font-medium hover:underline"
          >
            {row.stack}
          </Link>
          {showKind && (
            <Badge variant="outline" className="font-mono">
              {row.kind}
            </Badge>
          )}
        </div>
      </TableCell>
      <TableCell>
        {row.lastUpdate ? (
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={row.lastUpdate.status} />
            <KindBadge kind={row.lastUpdate.kind} />
            <Timestamp value={row.lastUpdate.startedAt} />
            <span className="font-mono text-xs text-muted-foreground">
              v{row.lastUpdate.version}
            </span>
          </div>
        ) : (
          <span className="text-muted-foreground">No updates</span>
        )}
      </TableCell>
      <TableCell className="text-right font-mono">
        {row.resourceCount ?? <span className="text-muted-foreground">-</span>}
      </TableCell>
      <TableCell>
        <div className="flex items-center gap-2">
          {isActiveUpdate(row) ? (
            <RunningIndicator row={row} />
          ) : (
            <Badge variant="muted">idle</Badge>
          )}
        </div>
      </TableCell>
    </TableRow>
  );
}

function RunningIndicator({ row }: { row: Workspace }) {
  const lock = row.lock;
  const lockedAtLabel = lock ? formatLockTimestamp(lock.lockedAt) : null;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant="info">
          <span className="size-1.5 rounded-full bg-current animate-pulse" />
          running
        </Badge>
      </TooltipTrigger>
      <TooltipContent>
        {lock
          ? `Update lease held by ${lock.lockedBy} since ${lockedAtLabel}`
          : "Update in progress"}
      </TooltipContent>
    </Tooltip>
  );
}

function formatLockTimestamp(value: string) {
  const lockedAt = new Date(value);
  return Number.isNaN(lockedAt.getTime()) ? value : lockedAt.toISOString();
}

export function StacksTableSkeleton() {
  return (
    <div className="rounded-md border bg-card">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Stack</TableHead>
            <TableHead>Last update</TableHead>
            <TableHead className="text-right">Resources</TableHead>
            <TableHead>Status</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {Array.from({ length: 5 }).map((_, index) => (
            <TableRow key={index}>
              <TableCell>
                <Skeleton className="h-5 w-48" />
              </TableCell>
              <TableCell>
                <Skeleton className="h-5 w-72" />
              </TableCell>
              <TableCell>
                <Skeleton className="ml-auto h-5 w-12" />
              </TableCell>
              <TableCell>
                <Skeleton className="h-5 w-20" />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function FirstRunOnboarding() {
  const host =
    typeof window === "undefined" ? "{host}" : window.location.host;
  const origin =
    typeof window === "undefined" ? "https://{host}" : window.location.origin;

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
      <EmptyState
        icon={Terminal}
        title="Connect your Pulumi CLI"
        description="Run one stack against this backend and it will appear here."
        className="border-0"
      />
      <OnboardingStep
        step="1"
        title="Point Pulumi at state-db"
        command={`pulumi login ${origin}/pulumi`}
      />
      <Card>
        <CardHeader>
          <CardTitle>2. Get a CLI token</CardTitle>
          <CardDescription>
            Create a token here, or use the browser flow the CLI opens automatically.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild variant="outline">
            <Link to="/console/account/tokens">
              Open tokens
              <ExternalLink data-icon="inline-end" />
            </Link>
          </Button>
        </CardContent>
      </Card>
      <OnboardingStep
        step="3"
        title="Enable console permalinks"
        command={`export PULUMI_CONSOLE_DOMAIN=${host}`}
        description="Makes pulumi up print links back to this console."
      />
      <OnboardingStep step="4" title="Create or update a stack" command="pulumi up" />
      <p className="text-center text-sm text-muted-foreground">
        OpenTofu support is planned.
      </p>
    </div>
  );
}

function OnboardingStep({
  step,
  title,
  description,
  command,
}: {
  step: string;
  title: string;
  description?: string;
  command: string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {step}. {title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>
        <CopyCommand value={command} />
      </CardContent>
    </Card>
  );
}

function CopyCommand({ value }: { value: string }) {
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      toast("Could not copy to clipboard");
      return;
    }
    toast("Copied");
  }

  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted px-3 py-2">
      <code className="min-w-0 flex-1 truncate font-mono text-sm">{value}</code>
      <Button type="button" variant="ghost" size="icon" onClick={() => void copy()}>
        <Copy />
        <span className="sr-only">Copy command</span>
      </Button>
    </div>
  );
}

type ProjectGroup = {
  org: string;
  project: string;
  rows: Workspace[];
};

function groupByProject(rows: Workspace[]): ProjectGroup[] {
  const groups = new Map<string, ProjectGroup>();
  for (const row of rows) {
    const key = `${row.org}/${row.project}`;
    const group =
      groups.get(key) ??
      groups
        .set(key, { org: row.org, project: row.project, rows: [] })
        .get(key)!;
    group.rows.push(row);
  }
  return Array.from(groups.values());
}

export function matchesSearch(row: Workspace, search: string) {
  const term = search.trim().toLowerCase();
  if (!term) {
    return true;
  }
  return [row.org, row.project, row.stack]
    .some((value) => value.toLowerCase().includes(term));
}

export function matchesFilter(row: Workspace, filter: StackFilter) {
  if (filter === "all") return true;
  if (filter === "running") return isActiveUpdate(row);
  if (filter === "failed") return row.lastUpdate?.status === "failed";
  return true;
}

export function isActiveUpdate(row: Workspace) {
  return row.lock !== null || row.lastUpdate?.status === "in-progress";
}

export function isFailedUpdate(row: Workspace) {
  return row.lastUpdate?.status === "failed";
}

export function summarizeStacks(rows: Workspace[]) {
  return {
    projects: new Set(rows.map((row) => `${row.org}/${row.project}`)).size,
    stacks: rows.length,
    resources: rows.reduce((total, row) => total + (row.resourceCount ?? 0), 0),
    failed: rows.filter(isFailedUpdate).length,
    active: rows.filter(isActiveUpdate).length,
  };
}

function pluralize(noun: string, count: number) {
  return count === 1 ? noun : `${noun}s`;
}

export function compareWorkspace(a: Workspace, b: Workspace) {
  return (
    a.org.localeCompare(b.org) ||
    a.project.localeCompare(b.project) ||
    a.stack.localeCompare(b.stack)
  );
}

export function hasMultipleKinds(rows: Workspace[]) {
  return new Set(rows.map((row) => row.kind)).size > 1;
}
