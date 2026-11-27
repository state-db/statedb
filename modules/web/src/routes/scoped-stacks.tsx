import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { FolderX, Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { WorkspaceExplorer } from "@/components/console/WorkspaceExplorer";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  compareWorkspace,
  hasMultipleKinds,
  matchesSearch,
  StacksTable,
  StacksTableSkeleton,
} from "@/routes/home";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { useShell } from "@/components/shell/AppShell";
import {
  api,
  NotFoundError,
  UnauthorizedError,
  type Workspace,
} from "@/lib/api";
import { canManageOrganizationMembers } from "@/routes/org-members";

export function OrgStacksPage({ org }: { org: string }) {
  const { user, setSelectedOrg } = useShell();

  useEffect(() => setSelectedOrg(org), [org]);

  return (
    <WorkspaceExplorer
      org={org}
      scopedToOrg
      showManageMembers={canManageOrganizationMembers(user, org)}
    />
  );
}

export function ProjectStacksPage({
  org,
  project,
}: {
  org: string;
  project: string;
}) {
  const [search, setSearch] = useState("");
  const query = useQuery({
    queryKey: ["workspaces", { org, project }],
    queryFn: () => api.workspaces.list({ org, project }),
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 2,
    refetchInterval: (result) =>
      result.state.data?.items.some((row) => row.lastUpdate?.status === "in-progress")
        ? 3_000
        : false,
  });

  const unauthorized = useUnauthorizedRedirect(query.error);

  const rows = query.data?.items ?? [];
  const visibleRows = useMemo(
    () => rows.filter((row) => matchesSearch(row, search)).sort(compareWorkspace),
    [rows, search],
  );

  if (unauthorized) return null;
  if (query.error instanceof NotFoundError) {
    return (
      <EmptyState
        icon={FolderX}
        title="No such project"
        description={`The project ${org}/${project} does not exist or you cannot access it.`}
        action={
          <Button asChild variant="outline">
            <Link to="/console">Back to all stacks</Link>
          </Button>
        }
      />
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

  return (
    <div className="flex flex-col gap-4">
      <ScopedHeader
        project={project}
        rows={rows}
        search={search}
        onSearchChange={setSearch}
      />
      {query.isLoading ? (
        <StacksTableSkeleton />
      ) : visibleRows.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No stacks match"
          description="Try a different stack name."
        />
      ) : (
        <StacksTable
          rows={visibleRows}
          grouped={false}
          showKind={hasMultipleKinds(rows)}
        />
      )}
    </div>
  );
}

function ScopedHeader({
  project,
  rows,
  search,
  onSearchChange,
}: {
  project: string;
  rows: Workspace[];
  search: string;
  onSearchChange: (value: string) => void;
}) {
  const createdAt = rows[0]?.projectCreatedAt;
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">
          {project}
        </h1>
        <p className="text-sm text-muted-foreground">
          {`${rows.length} ${pluralize("stack", rows.length)}`}
          {createdAt && ` · Created ${formatDate(createdAt)}`}
        </p>
      </div>
      <div className="relative w-full sm:max-w-sm">
        <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
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

function pluralize(noun: string, count: number) {
  return count === 1 ? noun : `${noun}s`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(
    new Date(value),
  );
}
