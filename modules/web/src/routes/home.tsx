import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import {
  Copy,
  ExternalLink,
  Lock,
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
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, type Workspace } from "@/lib/api";

export function HomePage() {
  const { selectedOrg } = useShell();
  const [search, setSearch] = useState("");
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
  const visibleRows = useMemo(
    () =>
      rows
        .filter((row) => !selectedOrg || row.org === selectedOrg)
        .filter((row) => matchesSearch(row, search))
        .sort(compareWorkspace),
    [rows, search, selectedOrg],
  );
  const unauthorized = useUnauthorizedRedirect(query.error);

  if (unauthorized) {
    return null;
  }

  if (query.isLoading) {
    return (
      <div className="flex flex-col gap-4">
        <StacksHeader search={search} onSearchChange={setSearch} />
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
      <StacksHeader search={search} onSearchChange={setSearch} />
      {visibleRows.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No stacks match"
          description="Try a different stack, project, or organization name."
        />
      ) : (
        <StacksTable rows={visibleRows} grouped showKind={hasMultipleKinds(rows)} />
      )}
    </div>
  );
}

function StacksHeader({
  search,
  onSearchChange,
}: {
  search: string;
  onSearchChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <h1 className="text-2xl font-semibold tracking-tight">Stacks</h1>
      <div className="relative w-full sm:max-w-sm">
        <Search className="pointer-events-none absolute left-2.5 top-2.5 text-muted-foreground" />
        <Input
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
          placeholder="Search stacks..."
          className="pl-8"
        />
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
    <div className="rounded-md border bg-card">
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
        <TableCell colSpan={4} className="py-2 font-medium">
          <span className="text-muted-foreground">{group.org}</span>
          <span className="px-2 text-muted-foreground">/</span>
          {group.project}
        </TableCell>
      </TableRow>
      {group.rows.map((row) => (
        <StackRow key={row.id} row={row} showKind={showKind} />
      ))}
    </>
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
          {row.lock && <LockIndicator lock={row.lock} />}
          {row.lastUpdate?.status === "in-progress" && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Badge variant="info">
                  <span className="size-1.5 rounded-full bg-current animate-pulse" />
                  updating
                </Badge>
              </TooltipTrigger>
              <TooltipContent>Update in progress</TooltipContent>
            </Tooltip>
          )}
          {!row.lock && row.lastUpdate?.status !== "in-progress" && (
            <Badge variant="muted">idle</Badge>
          )}
        </div>
      </TableCell>
    </TableRow>
  );
}

function LockIndicator({ lock }: { lock: NonNullable<Workspace["lock"]> }) {
  const lockedAt = new Date(lock.lockedAt);
  const lockedAtLabel = Number.isNaN(lockedAt.getTime())
    ? lock.lockedAt
    : lockedAt.toISOString();
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge variant="outline">
          <Lock />
          locked
        </Badge>
      </TooltipTrigger>
      <TooltipContent>
        Locked by {lock.lockedBy} since {lockedAtLabel}
      </TooltipContent>
    </Tooltip>
  );
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
    await navigator.clipboard.writeText(value);
    toast("Copied");
  }

  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted px-3 py-2">
      <code className="min-w-0 flex-1 truncate font-mono text-sm">{value}</code>
      <Button type="button" variant="ghost" size="icon" onClick={copy}>
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
