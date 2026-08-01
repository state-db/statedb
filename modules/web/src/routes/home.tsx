import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Boxes,
  Copy,
  ExternalLink,
  Search,
  Terminal,
} from "lucide-react";
import { useMemo, useState } from "react";
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
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, type Workspace } from "@/lib/api";

type StackFilter = "all" | "running" | "failed";

export function HomePage() {
  const { selectedOrg } = useShell();
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<StackFilter>("all");
  const query = useQuery({
    queryKey: ["workspaces"],
    queryFn: () => api.workspaces.list(),
    refetchInterval: (result) => {
      const rows = result.state.data ?? [];
      if (rows.length === 0) {
        return 10_000;
      }
      return rows.some((row) => row.lastUpdate?.status === "in-progress")
        ? 3_000
        : false;
    },
  });

  const rows = query.data ?? [];
  const scopedRows = useMemo(
    () => rows.filter((row) => !selectedOrg || row.org === selectedOrg),
    [rows, selectedOrg],
  );
  const visibleRows = useMemo(
    () =>
      scopedRows
        .filter((row) => matchesFilter(row, filter))
        .filter((row) => matchesSearch(row, search))
        .sort(compareWorkspace),
    [scopedRows, filter, search],
  );
  const unauthorized = useUnauthorizedRedirect(query.error);

  if (unauthorized) {
    return null;
  }

  if (query.isLoading) {
    return (
      <div className="flex flex-col gap-4">
        <StacksHeader
          rows={scopedRows}
          search={search}
          onSearchChange={setSearch}
          filter={filter}
          onFilterChange={setFilter}
        />
        <StacksTableSkeleton />
      </div>
    );
  }

  if (query.error) {
    return (
      <ErrorAlert
        title="Could not load stacks"
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    );
  }

  if (rows.length === 0) {
    return <FirstRunOnboarding />;
  }

  return (
    <div className="flex flex-col gap-4">
      <StacksHeader
        rows={scopedRows}
        search={search}
        onSearchChange={setSearch}
        filter={filter}
        onFilterChange={setFilter}
      />
      {visibleRows.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No stacks match"
          description="Try a different search or status filter."
        />
      ) : (
        <StacksTable rows={visibleRows} grouped showKind={hasMultipleKinds(rows)} />
      )}
    </div>
  );
}

function StacksHeader({
  rows,
  search,
  onSearchChange,
  filter,
  onFilterChange,
}: {
  rows: Workspace[];
  search: string;
  onSearchChange: (value: string) => void;
  filter: StackFilter;
  onFilterChange: (value: StackFilter) => void;
}) {
  const summary = summarizeStacks(rows);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Stacks</h1>
        <p className="text-sm text-muted-foreground">
          {summary.projects} {pluralize("project", summary.projects)} · {summary.stacks}{" "}
          {pluralize("stack", summary.stacks)}
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        <OverviewStat
          icon={Boxes}
          label="Resources"
          value={summary.resources.toLocaleString()}
          detail="resources in latest state"
        />
        <OverviewStat
          icon={AlertTriangle}
          label="Failed"
          value={summary.failed.toLocaleString()}
          detail="failed updates"
          tone={summary.failed > 0 ? "warning" : "muted"}
        />
        <OverviewStat
          icon={Activity}
          label="Running"
          value={summary.active.toLocaleString()}
          detail="active updates"
          tone={summary.active > 0 ? "info" : "muted"}
        />
      </div>

      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <ToggleGroup
          type="single"
          variant="outline"
          value={filter}
          onValueChange={(value) => {
            if (isStackFilter(value)) onFilterChange(value);
          }}
          aria-label="Stack status filter"
          className="justify-start flex-wrap"
        >
          <ToggleGroupItem value="all">All</ToggleGroupItem>
          <ToggleGroupItem value="running">Running</ToggleGroupItem>
          <ToggleGroupItem value="failed">Failed</ToggleGroupItem>
        </ToggleGroup>
        <div className="relative w-full lg:max-w-sm">
          <Search className="pointer-events-none absolute left-2.5 top-2.5 text-muted-foreground" />
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

function OverviewStat({
  icon: Icon,
  label,
  value,
  detail,
  tone = "default",
}: {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  label: string;
  value: string;
  detail: string;
  tone?: "default" | "info" | "warning" | "muted";
}) {
  return (
    <Card className="rounded-lg shadow-none">
      <CardHeader className="flex flex-row items-center justify-between gap-3 pb-2">
        <CardDescription>{label}</CardDescription>
        <div className="flex size-8 items-center justify-center rounded-md bg-muted text-muted-foreground">
          <Icon />
        </div>
      </CardHeader>
      <CardContent className="flex items-end justify-between gap-3">
        <CardTitle className="font-mono text-2xl">{value}</CardTitle>
        <Badge variant={tone === "warning" ? "warning" : tone === "info" ? "info" : "muted"}>
          {detail}
        </Badge>
      </CardContent>
    </Card>
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

function isStackFilter(value: string): value is StackFilter {
  return ["all", "running", "failed"].includes(value);
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
