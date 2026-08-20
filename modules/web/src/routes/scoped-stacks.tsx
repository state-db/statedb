import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { FolderX, Search, Users } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  compareWorkspace,
  hasMultipleKinds,
  matchesSearch,
  Pagination,
  StacksTable,
  StacksTableSkeleton,
  useDebouncedValue,
  useScopedPage,
} from "@/routes/home";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { useShell } from "@/components/shell/AppShell";
import {
  api,
  NotFoundError,
  UnauthorizedError,
  type Workspace,
  type WorkspaceOverview,
} from "@/lib/api";
import { canManageOrganizationMembers } from "@/routes/org-members";

const EMPTY_OVERVIEW: WorkspaceOverview = {
  stacks: 0,
  projects: 0,
  resources: 0,
  failed: 0,
  running: 0,
};

export function OrgStacksPage({ org }: { org: string }) {
  const { user, setSelectedOrg } = useShell();
  const [searchInput, setSearchInput] = useState("");
  const search = useDebouncedValue(searchInput, 300);
  const scopeKey = `${org}|${search}`;
  const [page, setPage] = useScopedPage(scopeKey);

  const listQuery = useQuery({
    queryKey: ["workspaces", { org, search, page }],
    queryFn: () => api.workspaces.list({ org, search: search || undefined, page }),
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError || error instanceof NotFoundError) &&
      failureCount < 2,
    placeholderData: keepPreviousData,
    refetchInterval: (result) =>
      result.state.data?.items.some((row) => row.lastUpdate?.status === "in-progress")
        ? 3_000
        : false,
  });

  const summaryQuery = useQuery({
    queryKey: ["workspaces", "summary", org],
    queryFn: () => api.workspaces.summary(org),
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError) && failureCount < 2,
    refetchInterval: (result) => (result.state.data?.running ? 5_000 : 15_000),
  });

  const unauthorized = useUnauthorizedRedirect(listQuery.error ?? summaryQuery.error);

  useEffect(() => setSelectedOrg(org), [org]);

  if (unauthorized) return null;
  if (listQuery.error instanceof NotFoundError && summaryQuery.data?.stacks === 0) {
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
  if (summaryQuery.error) {
    return (
      <ErrorAlert
        title="Could not load stacks"
        error={summaryQuery.error}
        onRetry={() => void summaryQuery.refetch()}
      />
    );
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

  const overview = summaryQuery.data ?? EMPTY_OVERVIEW;
  const result = listQuery.data;
  const rows = result?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <OrgHeader
        org={org}
        overview={overview}
        search={searchInput}
        onSearchChange={setSearchInput}
        showManageMembers={canManageOrganizationMembers(user, org)}
      />
      {listQuery.isLoading || summaryQuery.isLoading ? (
        <StacksTableSkeleton />
      ) : overview.stacks === 0 ? (
        <EmptyState
          icon={FolderX}
          title="No stacks in this organization"
          description="Stacks appear here after they are created with the CLI."
        />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={Search}
          title="No stacks match"
          description="Try a different stack or project name."
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

function OrgHeader({
  org,
  overview,
  search,
  onSearchChange,
  showManageMembers,
}: {
  org: string;
  overview: WorkspaceOverview;
  search: string;
  onSearchChange: (value: string) => void;
  showManageMembers: boolean;
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">{org}</h1>
        <p className="text-sm text-muted-foreground">
          {`${overview.projects} ${pluralize("project", overview.projects)} · ${overview.stacks} ${pluralize("stack", overview.stacks)}`}
        </p>
      </div>
      <div className="flex w-full items-center gap-2 sm:w-auto">
        {showManageMembers && (
          <Button asChild variant="outline">
            <Link to="/console/admin/orgs/$org" params={{ org }}>
              <Users data-icon="inline-start" />
              Manage members
            </Link>
          </Button>
        )}
        <div className="relative min-w-0 flex-1 sm:w-80">
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

function pluralize(noun: string, count: number) {
  return count === 1 ? noun : `${noun}s`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(
    new Date(value),
  );
}
