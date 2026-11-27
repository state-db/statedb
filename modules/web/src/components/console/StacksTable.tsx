import { Link } from "@tanstack/react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useState } from "react";

import { KindBadge, StatusBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
import { type Workspace, type WorkspaceLastUpdate } from "@/lib/api";

export type StackFilter = "all" | "running" | "failed";

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
                  <ProjectRows
                    key={`${group.org}/${group.project}`}
                    group={group}
                    showKind={showKind}
                  />
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
            <LastUpdateVersion update={row.lastUpdate} />
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
            <LastUpdateVersion update={row.lastUpdate} />
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
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant="info">
          <span className="size-1.5 rounded-full bg-current animate-pulse" />
          running
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{runningIndicatorLabel(row)}</TooltipContent>
    </Tooltip>
  );
}

export function runningIndicatorLabel(row: Workspace): string {
  const actor = row.lastUpdate?.actor;
  const actorLabel = actor?.displayName ?? actor?.email ?? null;
  const lockedAtLabel = row.lock ? formatLockTimestamp(row.lock.lockedAt) : null;
  if (actorLabel && lockedAtLabel) return `${actorLabel} started this run · active since ${lockedAtLabel}`;
  if (actorLabel) return `${actorLabel} started this run`;
  if (lockedAtLabel) return `Update in progress since ${lockedAtLabel}`;
  return "Update in progress";
}

function LastUpdateVersion({ update }: { update: WorkspaceLastUpdate }) {
  const label = lastUpdateVersionLabel(update);
  return label ? <span className="font-mono text-xs text-muted-foreground">{label}</span> : null;
}

export function lastUpdateVersionLabel(update: WorkspaceLastUpdate): string | null {
  return update.version === null ? null : `v${update.version}`;
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
  if (filter === "failed") return isFailedUpdate(row);
  return true;
}

export function isActiveUpdate(row: Workspace) {
  return row.lock !== null || row.lastUpdate?.status === "in-progress";
}

export function isFailedUpdate(row: Workspace) {
  if (typeof row.failed === "boolean") return row.failed;
  return row.lastUpdate?.status === "failed" && row.lastUpdate.version !== null;
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
