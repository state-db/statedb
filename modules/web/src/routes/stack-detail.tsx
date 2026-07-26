import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import type { ColumnDef } from "@tanstack/react-table";
import {
  ChevronDown,
  ChevronRight,
  Check,
  Copy,
  Download,
  EyeOff,
  List,
  Lock,
  Network,
  Search,
  Trash2,
  Unlock,
} from "lucide-react";
import { memo, useCallback, useMemo, useState } from "react";
import { toast } from "sonner";

import { CopyableId } from "@/components/console/CopyableId";
import { DataTable } from "@/components/console/DataTable";
import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { isPulumiSecret, SecretBadge } from "@/components/console/SecretBadge";
import { KindBadge, StatusBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
  api,
  NotFoundError,
  type PulumiResource,
  type PulumiValue,
  type StackDetail,
  type UpdateHistoryRow,
} from "@/lib/api";
import { cn } from "@/lib/utils";
import { useCurrentUser } from "@/hooks/useCurrentUser";

type ViewMode = "flat" | "tree";
export type StackTab = "resources" | "outputs" | "activity" | "settings";
const RESOURCE_PAGE_SIZE = 50;
const RESOURCE_COLLATOR = new Intl.Collator();
const TREE_INDENT_REM = 1;
const TREE_MAX_INDENT_REM = 7;
const TREE_EXPAND_ALL_LIMIT = 200;
const TREE_AUTO_EXPAND_FROM_DEPTH = 5;

export function StackDetailPage({
  org,
  project,
  stack,
  tab,
  onTabChange,
}: {
  org: string;
  project: string;
  stack: string;
  tab: StackTab;
  onTabChange: (tab: StackTab) => void;
}) {
  const query = useQuery({
    queryKey: ["workspace", org, project, stack],
    queryFn: () => api.workspaces.get(org, project, stack),
    refetchInterval: (current) =>
      current.state.data?.lastUpdate?.status === "in-progress" ? 3_000 : false,
  });
  const user = useCurrentUser();
  const [filter, setFilter] = useState("");
  const [selectedUrn, setSelectedUrn] = useState<string | null>(null);
  const [resourcePage, setResourcePage] = useState(1);
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    const saved = window.localStorage.getItem("statedb.resources.view");
    return saved === "tree" ? "tree" : "flat";
  });

  const resources = query.data?.resources ?? [];
  const resourceRows = useMemo(() => buildResourceRows(resources), [resources]);
  const visible = useMemo(() => filterResourceRows(resourceRows, filter), [filter, resourceRows]);
  const resourceByUrn = useMemo(
    () => new Map(resources.map((resource) => [resource.urn, resource])),
    [resources],
  );
  const filterActive = filter.trim().length > 0;
  const resourcePageInfo = pageResources(visible, resourcePage, RESOURCE_PAGE_SIZE);
  const treeResources = filterActive ? resourcePageInfo.rows : visible;
  const selected = selectedUrn ? resourceByUrn.get(selectedUrn) ?? null : null;
  const outputs = stackOutputs(resources);

  if (query.isLoading) return <StackDetailSkeleton />;
  if (query.error instanceof NotFoundError) {
    return (
      <EmptyState
        title="Stack not found"
        description="This stack does not exist or you do not have access to it."
      />
    );
  }
  if (query.error || !query.data) {
    return (
      <ErrorAlert
        title="Could not load stack"
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    );
  }

  const detail = query.data;
  const updateInProgress = detail.lastUpdate?.status === "in-progress";

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <h1 className="text-2xl font-semibold tracking-tight">
              {detail.org} / {detail.project} / {detail.name}
            </h1>
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <span>v{detail.version}</span>
              <span aria-hidden="true">·</span>
              <span>{detail.resourceCount ?? resources.length} resources</span>
              {detail.lastUpdate && (
                <>
                  <span aria-hidden="true">·</span>
                  <StatusBadge status={detail.lastUpdate.status} />
                  <Timestamp value={detail.lastUpdate.startedAt} />
                </>
              )}
              {detail.pulumiVersion && (
                <>
                  <span aria-hidden="true">·</span>
                  <span>Pulumi {detail.pulumiVersion}</span>
                </>
              )}
            </div>
          </div>
          {tab === "resources" && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="outline" asChild>
                  <a href={api.workspaces.exportUrl(org, project, stack)} download>
                    <Download data-icon="inline-start" />
                    Download state
                  </a>
                </Button>
              </TooltipTrigger>
              <TooltipContent>Download the raw Pulumi checkpoint JSON.</TooltipContent>
            </Tooltip>
          )}
        </div>
        {detail.lock && (
          <Alert>
            <Lock />
            <AlertTitle>Stack locked by {detail.lock.lockedBy}</AlertTitle>
            <AlertDescription>
              Locked <Timestamp value={detail.lock.lockedAt} />. Force unlock is available from
              Operations.
            </AlertDescription>
          </Alert>
        )}
        {updateInProgress && detail.lastUpdate && (
          <Alert>
            <Network />
            <AlertTitle>Update v{detail.lastUpdate.version} in progress</AlertTitle>
            <AlertDescription>
              Resources shown are from v{detail.version}.{" "}
              <Link
                className="underline underline-offset-4"
                to="/console/$org/$project/$stack/updates/$version"
                params={{ org, project, stack, version: String(detail.lastUpdate.version) }}
              >
                View live run
              </Link>
            </AlertDescription>
          </Alert>
        )}
      </header>

      <Tabs
        value={tab}
        onValueChange={(value) => {
          if (value === "resources" || value === "outputs" || value === "activity" || value === "settings") {
            onTabChange(value);
          }
        }}
      >
        <TabsList>
          <TabsTrigger value="resources">Resources</TabsTrigger>
          <TabsTrigger value="outputs">Outputs</TabsTrigger>
          <TabsTrigger value="activity">Activity</TabsTrigger>
          <TabsTrigger value="settings">Settings</TabsTrigger>
        </TabsList>
      </Tabs>

      {tab === "resources" ? (
        <section className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="relative w-full max-w-sm">
              <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-9"
                placeholder="Filter by type or name…"
                value={filter}
                onChange={(event) => {
                  setFilter(event.target.value);
                  setResourcePage(1);
                }}
                aria-label="Filter resources by type or name"
              />
            </div>
            <ToggleGroup
              type="single"
              variant="outline"
              value={viewMode}
              onValueChange={(value) => {
                if (value !== "flat" && value !== "tree") return;
                setViewMode(value);
                window.localStorage.setItem("statedb.resources.view", value);
              }}
              aria-label="Resource view"
            >
              <ToggleGroupItem value="flat" aria-label="Flat view">
                <List /> Flat
              </ToggleGroupItem>
              <ToggleGroupItem value="tree" aria-label="Tree view">
                <Network /> Tree
              </ToggleGroupItem>
            </ToggleGroup>
          </div>

          {resources.length === 0 ? (
            <EmptyState
              title="No resources"
              description="Run pulumi up on this stack to deploy resources."
            />
          ) : visible.length === 0 ? (
            <EmptyState
              title="No resources match"
              description="Try a different type or resource name."
            />
          ) : viewMode === "flat" ? (
            <>
              <ResourceTable resources={resourcePageInfo.rows} onSelect={setSelectedUrn} />
              <ResourcePager pageInfo={resourcePageInfo} onPageChange={setResourcePage} />
            </>
          ) : (
            <>
              <ResourceTree
                resources={treeResources}
                allResources={resources}
                includeAncestors={filterActive}
                expandAll={visible.length <= TREE_EXPAND_ALL_LIMIT}
                onSelect={setSelectedUrn}
              />
              {filterActive && (
                <ResourcePager pageInfo={resourcePageInfo} onPageChange={setResourcePage} />
              )}
            </>
          )}
        </section>
      ) : tab === "outputs" ? (
        <StackOutputs outputs={outputs} />
      ) : tab === "activity" ? (
        <StackActivity org={org} project={project} stack={stack} />
      ) : (
        <StackSettings detail={detail} isAdmin={user.data?.siteAdmin === true} />
      )}

      <ResourceSheet
        resource={selected}
        resourceByUrn={resourceByUrn}
        onOpenChange={(open) => !open && setSelectedUrn(null)}
        onNavigate={setSelectedUrn}
      />
    </div>
  );
}

function StackSettings({ detail, isAdmin }: { detail: StackDetail; isAdmin: boolean }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [confirmation, setConfirmation] = useState("");
  const queryKey = ["workspace", detail.org, detail.project, detail.name];

  const unlock = useMutation({
    mutationFn: () => api.workspaces.forceUnlock(detail.org, detail.project, detail.name, detail.lock!.lockId),
    onSuccess: async () => {
      toast.success("Stack unlocked");
      await queryClient.invalidateQueries({ queryKey });
    },
    onError: (error) => toast.error("Could not unlock stack", { description: error.message }),
  });
  const compact = useMutation({
    mutationFn: () => api.workspaces.compact(detail.org, detail.project, detail.name),
    onSuccess: () => toast.success("Compaction queued"),
    onError: (error) => toast.error("Could not queue compaction", { description: error.message }),
  });
  const deleteStack = useMutation({
    mutationFn: () => api.workspaces.delete(detail.org, detail.project, detail.name),
    onSuccess: () => {
      toast.success("Stack deleted");
      void navigate({ to: "/console" });
    },
    onError: (error) => toast.error("Could not delete stack", { description: error.message }),
  });
  const empty = detail.resourceCount === 0;

  return (
    <section className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
          <CardDescription>Read-only stack metadata.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Detail label="Created"><Timestamp value={detail.createdAt} /></Detail>
          <Detail label="Workspace ID"><span className="font-mono text-xs">{detail.workspaceId}</span></Detail>
          <Detail label="Stack UUID"><CopyableId value={detail.id} /></Detail>
          <Detail label="Tags">
            <div className="flex flex-wrap gap-2">
              {Object.entries(detail.tags).length === 0 ? (
                <span className="text-muted-foreground">No tags</span>
              ) : (
                Object.entries(detail.tags).map(([key, value]) => (
                  <Badge key={key} variant="secondary">{key}={value}</Badge>
                ))
              )}
            </div>
          </Detail>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Locking</CardTitle>
          <CardDescription>Current Pulumi update lease state.</CardDescription>
        </CardHeader>
        <CardContent>
          {detail.lock ? (
            <p className="text-sm">Locked by <span className="font-medium">{detail.lock.lockedBy}</span> <Timestamp value={detail.lock.lockedAt} />.</p>
          ) : (
            <p className="text-sm text-muted-foreground">This stack is not locked.</p>
          )}
        </CardContent>
        {isAdmin && detail.lock && (
          <CardFooter>
            <AlertDialog>
              <AlertDialogTrigger asChild><Button variant="destructive"><Unlock data-icon="inline-start" />Force unlock</Button></AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Force unlock {detail.name}?</AlertDialogTitle>
                  <AlertDialogDescription>The holder&apos;s pulumi up will fail and may need to be run again. Only continue if the active operation is no longer healthy.</AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction className={buttonVariants({ variant: "destructive" })} disabled={unlock.isPending} onClick={() => unlock.mutate()}>Force unlock</AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </CardFooter>
        )}
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Compaction</CardTitle>
          <CardDescription>Snapshot and journal replay health.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-3">
          <Detail label="Base snapshot version">v{detail.baseSnapshotVersion}</Detail>
          <Detail label="Journal entries">{detail.journalEntriesSinceCompaction}</Detail>
          <Detail label="Auto-compact threshold">{detail.autoCompactThreshold}</Detail>
        </CardContent>
        {isAdmin && (
          <CardFooter><Button variant="outline" disabled={compact.isPending} onClick={() => compact.mutate()}>Compact now</Button></CardFooter>
        )}
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Danger zone</CardTitle>
          <CardDescription>Deleting a stack permanently removes its state and history.</CardDescription>
        </CardHeader>
        {isAdmin && (
          <CardFooter>
            <AlertDialog onOpenChange={(open) => !open && setConfirmation("")}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <span>
                    <AlertDialogTrigger asChild>
                      <Button variant="destructive" disabled={!empty}><Trash2 data-icon="inline-start" />Delete stack</Button>
                    </AlertDialogTrigger>
                  </span>
                </TooltipTrigger>
                {!empty && <TooltipContent>Pulumi only permits deleting an empty stack. Destroy its resources first.</TooltipContent>}
              </Tooltip>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {detail.name} permanently?</AlertDialogTitle>
                  <AlertDialogDescription>This removes the stack, its state, and its complete update history. This action cannot be undone.</AlertDialogDescription>
                </AlertDialogHeader>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="delete-stack-confirmation">Type <span className="font-mono">{detail.name}</span> to confirm</Label>
                  <Input id="delete-stack-confirmation" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="off" />
                </div>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction className={buttonVariants({ variant: "destructive" })} disabled={confirmation !== detail.name || deleteStack.isPending} onClick={() => deleteStack.mutate()}>Delete stack</AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </CardFooter>
        )}
      </Card>
    </section>
  );
}

function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-sm font-medium">{label}</span>
      <div className="text-sm text-muted-foreground">{children}</div>
    </div>
  );
}

const UPDATE_KINDS = ["update", "preview", "refresh", "destroy", "import", "rename"];
const UPDATE_STATUSES = ["in-progress", "succeeded", "failed", "cancelled"];

function StackActivity({ org, project, stack }: { org: string; project: string; stack: string }) {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [kind, setKind] = useState("all");
  const [status, setStatus] = useState("all");
  const history = useQuery({
    queryKey: ["stack-updates", org, project, stack, page, kind, status],
    queryFn: () =>
      api.workspaces.updates(org, project, stack, {
        page,
        kind: kind === "all" ? undefined : kind,
        status: status === "all" ? undefined : status,
      }),
    refetchInterval: (query) =>
      query.state.data?.updates.some((update) => update.status === "in-progress") ? 3_000 : false,
  });

  const columns = useMemo<ColumnDef<UpdateHistoryRow>[]>(
    () => [
      {
        accessorKey: "version",
        header: "Version",
        cell: ({ row }) => (
          <span className="font-mono">{row.original.version == null ? "—" : `v${row.original.version}`}</span>
        ),
      },
      { accessorKey: "kind", header: "Kind", cell: ({ row }) => <KindBadge kind={row.original.kind} /> },
      { accessorKey: "status", header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
      {
        accessorKey: "message",
        header: "Message",
        cell: ({ row }) => {
          const message = row.original.message;
          if (!message) return <span className="text-muted-foreground">—</span>;
          return (
            <Tooltip>
              <TooltipTrigger asChild><span className="block max-w-72 truncate">{message}</span></TooltipTrigger>
              <TooltipContent className="max-w-sm">{message}</TooltipContent>
            </Tooltip>
          );
        },
      },
      { id: "changes", header: "Changes", cell: ({ row }) => <ChangeSummary summary={row.original.summary} /> },
      { id: "duration", header: "Duration", cell: ({ row }) => formatDuration(row.original.startedAt, row.original.endedAt) },
      { accessorKey: "startedAt", header: "Started", cell: ({ row }) => <Timestamp value={row.original.startedAt} /> },
    ],
    [],
  );

  if (history.isLoading) return <ActivitySkeleton />;
  if (history.error || !history.data) {
    return <ErrorAlert title="Could not load activity" error={history.error} onRetry={() => void history.refetch()} />;
  }
  const pageCount = Math.max(1, Math.ceil(history.data.total / history.data.pageSize));
  return (
    <DataTable
      columns={columns}
      data={history.data.updates}
      emptyTitle="No updates yet"
      emptyDescription="Update and preview runs for this stack will appear here."
      toolbar={
        <div className="flex flex-wrap gap-2">
          <HistorySelect label="All kinds" value={kind} values={UPDATE_KINDS} onChange={(value) => { setKind(value); setPage(1); }} />
          <HistorySelect label="All statuses" value={status} values={UPDATE_STATUSES} onChange={(value) => { setStatus(value); setPage(1); }} />
        </div>
      }
      pagination={{ page, pageCount, onPageChange: setPage }}
      onRowClick={(update) => {
        if (update.version == null) {
          void navigate({ to: "/console/$org/$project/$stack/previews/$updateId", params: { org, project, stack, updateId: update.id } });
        } else {
          void navigate({ to: "/console/$org/$project/$stack/updates/$version", params: { org, project, stack, version: String(update.version) } });
        }
      }}
    />
  );
}

function HistorySelect({ label, value, values, onChange }: { label: string; value: string; values: string[]; onChange: (value: string) => void }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="w-44" aria-label={label}><SelectValue /></SelectTrigger>
      <SelectContent><SelectGroup><SelectItem value="all">{label}</SelectItem>{values.map((item) => <SelectItem key={item} value={item}>{item}</SelectItem>)}</SelectGroup></SelectContent>
    </Select>
  );
}

function ChangeSummary({ summary }: { summary: UpdateHistoryRow["summary"] }) {
  const changes = summary?.resourceChanges;
  if (!changes) return <span className="text-muted-foreground">—</span>;
  const { added, changed, deleted } = calculateChangeSummary(changes);
  if (added + changed + deleted === 0) return <span className="font-mono text-muted-foreground">no changes</span>;
  return (
    <span className="flex gap-1 font-mono">
      {added > 0 && <Badge variant="success">+{added}</Badge>}
      {changed > 0 && <Badge variant="warning">~{changed}</Badge>}
      {deleted > 0 && <Badge variant="destructive">-{deleted}</Badge>}
    </span>
  );
}

export function calculateChangeSummary(changes: Record<string, number>) {
  return {
    added: (changes.create ?? 0) + (changes["create-replacement"] ?? 0),
    // A replacement already contributes to create-replacement and delete-replaced. Counting the
    // accompanying replace marker as an update would report the same resource three times.
    changed: changes.update ?? 0,
    deleted: (changes.delete ?? 0) + (changes["delete-replaced"] ?? 0),
  };
}

export function formatDuration(startedAt: string, endedAt: string | null): string {
  if (!endedAt) return "—";
  const seconds = Math.max(0, Math.round((Date.parse(endedAt) - Date.parse(startedAt)) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${seconds % 60}s`;
}

export interface ResourcePage {
  page: number;
  pageCount: number;
  pageSize: number;
  total: number;
  start: number;
  end: number;
  rows: PulumiResource[];
}

export interface ResourceListRow {
  resource: PulumiResource;
  type: string;
  name: string;
  searchText: string;
}

export function buildResourceRows(resources: PulumiResource[]): ResourceListRow[] {
  return resources
    .map((resource) => {
      const type = resourceType(resource);
      const name = resourceName(resource);
      return {
        resource,
        type,
        name,
        searchText: `${type} ${name}`.toLowerCase(),
      };
    })
    .sort(
      (a, b) =>
        RESOURCE_COLLATOR.compare(a.type, b.type) || RESOURCE_COLLATOR.compare(a.name, b.name),
    );
}

export function filterResourceRows(rows: ResourceListRow[], filter: string): PulumiResource[] {
  const needle = filter.trim().toLowerCase();
  if (!needle) return rows.map((row) => row.resource);
  return rows.filter((row) => row.searchText.includes(needle)).map((row) => row.resource);
}

export function filterAndSortResources(resources: PulumiResource[], filter: string): PulumiResource[] {
  return filterResourceRows(buildResourceRows(resources), filter);
}

export function pageResources(
  resources: PulumiResource[],
  page: number,
  pageSize: number,
): ResourcePage {
  const total = resources.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), pageCount);
  const start = (safePage - 1) * pageSize;
  const end = Math.min(start + pageSize, total);
  return {
    page: safePage,
    pageCount,
    pageSize,
    total,
    start,
    end,
    rows: resources.slice(start, end),
  };
}

export interface ResourceTreeModel {
  roots: PulumiResource[];
  children: Map<string, PulumiResource[]>;
  childCounts: Map<string, number>;
  autoExpanded: Set<string>;
}

export function defaultTreeBranchOpen(
  depth: number,
  options: { expandAll?: boolean } = {},
): boolean {
  return options.expandAll === true || depth < 1 || depth >= TREE_AUTO_EXPAND_FROM_DEPTH;
}

export function resourceTreeIndentRem(depth: number): number {
  return Math.min(Math.max(0, depth), TREE_MAX_INDENT_REM) * TREE_INDENT_REM;
}

export function buildResourceTree(
  resources: PulumiResource[],
  allResources: PulumiResource[],
  options: { includeAncestors?: boolean } = {},
): ResourceTreeModel {
  const byUrn = new Map(allResources.map((resource) => [resource.urn, resource]));
  const included = new Set(resources.map((resource) => resource.urn));
  const autoExpanded = new Set<string>();

  if (options.includeAncestors) {
    for (const resource of resources) {
      const seen = new Set<string>([resource.urn]);
      let parent = resource.parent ? byUrn.get(resource.parent) : undefined;
      while (parent && !seen.has(parent.urn)) {
        included.add(parent.urn);
        autoExpanded.add(parent.urn);
        seen.add(parent.urn);
        parent = parent.parent ? byUrn.get(parent.parent) : undefined;
      }
    }
  }

  const ordered = [
    ...allResources.filter((resource) => included.has(resource.urn)),
    ...resources.filter((resource) => !byUrn.has(resource.urn)),
  ];
  const visibleUrns = new Set(ordered.map((resource) => resource.urn));
  const children = new Map<string, PulumiResource[]>();

  for (const resource of ordered) {
    if (!resource.parent || !visibleUrns.has(resource.parent)) continue;
    const bucket = children.get(resource.parent);
    if (bucket) {
      bucket.push(resource);
    } else {
      children.set(resource.parent, [resource]);
    }
  }

  const childCounts = new Map<string, number>();
  for (const [parent, descendants] of children) {
    childCounts.set(parent, descendants.length);
  }

  return {
    roots: ordered.filter((resource) => !resource.parent || !visibleUrns.has(resource.parent)),
    children,
    childCounts,
    autoExpanded,
  };
}

function ActivitySkeleton() {
  return <div className="flex flex-col gap-2">{Array.from({ length: 5 }, (_, index) => <Skeleton key={index} className="h-12 w-full" />)}</div>;
}

function StackOutputs({ outputs }: { outputs: Record<string, PulumiValue> }) {
  const entries = Object.entries(outputs).sort(([left], [right]) => left.localeCompare(right));

  return (
    <section className="flex flex-col gap-4">
      <Alert>
        <EyeOff />
        <AlertDescription>
          Secret outputs can be read with <code>pulumi stack output --show-secrets</code>.
        </AlertDescription>
      </Alert>
      {entries.length === 0 ? (
        <EmptyState
          title="This stack has no outputs"
          description="Export values with pulumi.export(...)."
        />
      ) : (
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-1/3">Key</TableHead>
                <TableHead>Value</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {entries.map(([key, value]) => (
                <TableRow key={key}>
                  <TableCell className="font-medium">{key}</TableCell>
                  <TableCell className="min-w-0">
                    <OutputValue value={value} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </section>
  );
}

function OutputValue({ value }: { value: PulumiValue }) {
  if (containsSecret(value)) return <SecretBadge />;
  if (value !== null && typeof value === "object") {
    const raw = serializeOutput(value);
    return (
      <Collapsible>
        <div className="flex items-center gap-1">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm">
              <ChevronRight data-icon="inline-start" />
              JSON
            </Button>
          </CollapsibleTrigger>
          <CopyButton value={raw} label="Copy JSON output" />
        </div>
        <CollapsibleContent>
          <pre className="mt-2 max-h-80 overflow-auto whitespace-pre rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
            <code>{JSON.stringify(value, null, 2)}</code>
          </pre>
        </CollapsibleContent>
      </Collapsible>
    );
  }
  const raw = serializeOutput(value);
  return (
    <div className="flex min-w-0 items-center gap-1">
      <code className="min-w-0 flex-1 truncate font-mono text-xs" title={raw}>{raw}</code>
      <CopyButton value={raw} label="Copy output" />
    </div>
  );
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    toast("Copied");
    window.setTimeout(() => setCopied(false), 1200);
  }
  return (
    <Button type="button" variant="ghost" size="icon" onClick={copy} aria-label={label}>
      {copied ? <Check /> : <Copy />}
    </Button>
  );
}

function ResourceTable({
  resources,
  onSelect,
}: {
  resources: PulumiResource[];
  onSelect: (urn: string) => void;
}) {
  return (
    <div className="rounded-md border">
      <Table className="min-w-[900px] table-fixed">
        <ResourceTableHeader />
        <TableBody>
          {resources.map((resource) => (
            <ResourceRow key={resource.urn} resource={resource} onSelect={onSelect} />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function ResourceTree({
  resources,
  allResources,
  includeAncestors,
  expandAll,
  onSelect,
}: {
  resources: PulumiResource[];
  allResources: PulumiResource[];
  includeAncestors: boolean;
  expandAll: boolean;
  onSelect: (urn: string) => void;
}) {
  const model = useMemo(
    () => buildResourceTree(resources, allResources, { includeAncestors }),
    [resources, allResources, includeAncestors],
  );

  return (
    <div className="rounded-md border">
      <Table className="min-w-[900px] table-fixed">
        <ResourceTableHeader />
        <TableBody>
          {model.roots.map((resource) => (
            <TreeBranch
              key={resource.urn}
              resource={resource}
              children={model.children}
              childCounts={model.childCounts}
              autoExpanded={model.autoExpanded}
              expandAll={expandAll}
              depth={0}
              onSelect={onSelect}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function TreeBranch({
  resource,
  children,
  childCounts,
  autoExpanded,
  expandAll,
  depth,
  onSelect,
}: {
  resource: PulumiResource;
  children: Map<string, PulumiResource[]>;
  childCounts: Map<string, number>;
  autoExpanded: Set<string>;
  expandAll: boolean;
  depth: number;
  onSelect: (urn: string) => void;
}) {
  const descendants = children.get(resource.urn) ?? [];
  const [open, setOpen] = useState(defaultTreeBranchOpen(depth, { expandAll }));
  const toggle = useCallback(() => setOpen((value) => !value), []);
  const forcedOpen = autoExpanded.has(resource.urn);
  const expanded = expandAll || forcedOpen || open;
  return (
    <>
      <ResourceRow
        resource={resource}
        depth={depth}
        childCount={childCounts.get(resource.urn) ?? 0}
        open={expanded}
        onToggle={descendants.length ? toggle : undefined}
        onSelect={onSelect}
      />
      {expanded && descendants.map((child) => (
        <TreeBranch
          key={child.urn}
          resource={child}
          children={children}
          childCounts={childCounts}
          autoExpanded={autoExpanded}
          expandAll={expandAll}
          depth={depth + 1}
          onSelect={onSelect}
        />
      ))}
    </>
  );
}

function ResourcePager({
  pageInfo,
  onPageChange,
}: {
  pageInfo: ResourcePage;
  onPageChange: (page: number) => void;
}) {
  if (pageInfo.pageCount <= 1) return null;
  return (
    <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
      <span>
        Showing {pageInfo.start + 1}-{pageInfo.end} of {pageInfo.total}
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={pageInfo.page <= 1}
          onClick={() => onPageChange(pageInfo.page - 1)}
        >
          Previous
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={pageInfo.page >= pageInfo.pageCount}
          onClick={() => onPageChange(pageInfo.page + 1)}
        >
          Next
        </Button>
      </div>
    </div>
  );
}

function ResourceTableHeader() {
  return (
    <TableHeader>
      <TableRow>
        <TableHead className="w-[34%]">Type</TableHead>
        <TableHead className="w-[18%]">Name</TableHead>
        <TableHead className="w-[10%]">Provider</TableHead>
        <TableHead className="w-[10%]">Kind</TableHead>
        <TableHead className="w-[14%]">Created</TableHead>
        <TableHead className="w-[14%]">Modified</TableHead>
      </TableRow>
    </TableHeader>
  );
}

const ResourceRow = memo(function ResourceRow({
  resource,
  onSelect,
  depth = 0,
  childCount = 0,
  open,
  onToggle,
}: {
  resource: PulumiResource;
  onSelect: (urn: string) => void;
  depth?: number;
  childCount?: number;
  open?: boolean;
  onToggle?: () => void;
}) {
  return (
    <TableRow
      className="cursor-pointer"
      onClick={() => onSelect(resource.urn)}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") onSelect(resource.urn);
      }}
      tabIndex={0}
    >
      <TableCell className="max-w-0 overflow-hidden">
        <div
          className="flex min-w-0 items-center gap-1 overflow-hidden"
          style={{ paddingLeft: `${resourceTreeIndentRem(depth)}rem` }}
        >
          {onToggle ? (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-6 shrink-0"
              onClick={(event) => {
                event.stopPropagation();
                onToggle();
              }}
              aria-label={open ? "Collapse children" : "Expand children"}
            >
              {open ? <ChevronDown /> : <ChevronRight />}
            </Button>
          ) : depth > 0 ? (
            <span className="w-6 shrink-0" />
          ) : null}
          <span className="min-w-0 truncate font-mono text-xs" title={resourceType(resource)}>
            {resourceType(resource)}
          </span>
          {childCount > 0 && (
            <span className="shrink-0 text-xs text-muted-foreground">({childCount})</span>
          )}
        </div>
      </TableCell>
      <TableCell className="max-w-0 font-medium">
        <span className="block truncate" title={resourceName(resource)}>
          {resourceName(resource)}
        </span>
      </TableCell>
      <TableCell className="max-w-0">
        <span className="block truncate" title={resourceProvider(resource)}>
          {resourceProvider(resource)}
        </span>
      </TableCell>
      <TableCell>
        <Badge variant="secondary">
          {resource.custom === false ? "component" : "custom"}
        </Badge>
      </TableCell>
      <TableCell>{resource.created ? <Timestamp value={resource.created} /> : "-"}</TableCell>
      <TableCell>{resource.modified ? <Timestamp value={resource.modified} /> : "-"}</TableCell>
    </TableRow>
  );
});
ResourceRow.displayName = "ResourceRow";

function ResourceSheet({
  resource,
  resourceByUrn,
  onOpenChange,
  onNavigate,
}: {
  resource: PulumiResource | null;
  resourceByUrn: Map<string, PulumiResource>;
  onOpenChange: (open: boolean) => void;
  onNavigate: (urn: string) => void;
}) {
  if (!resource) return null;
  const references = [
    ...(resource.parent ? [{ label: "Parent", urn: resource.parent }] : []),
    ...(resource.provider ? [{ label: "Provider", urn: providerUrn(resource.provider) }] : []),
    ...(resource.dependencies ?? []).map((urn) => ({ label: "Dependency", urn })),
  ];
  return (
    <Sheet open onOpenChange={onOpenChange}>
      <SheetContent className="flex w-full flex-col gap-0 overflow-hidden p-0 sm:max-w-[600px]">
        <SheetHeader className="gap-1 px-6 py-5 pr-14">
          <div className="flex min-w-0 items-center gap-2">
            <SheetTitle className="truncate" title={resourceName(resource)}>
              {resourceName(resource)}
            </SheetTitle>
            <Badge variant="secondary">
              {resource.custom === false ? "component" : "custom"}
            </Badge>
          </div>
          <SheetDescription className="truncate font-mono" title={resourceType(resource)}>
            {resourceType(resource)}
          </SheetDescription>
        </SheetHeader>
        <Separator />
        <ScrollArea className="min-h-0 flex-1">
          <div className="flex flex-col gap-8 px-6 py-6">
            <section className="flex flex-col gap-4">
              <SectionHeading title="Identity" />
              <div className="grid gap-4 rounded-lg border bg-muted/20 p-4 sm:grid-cols-2">
                <DetailField label="URN" className="sm:col-span-2">
                  <CopyableId value={resource.urn} className="w-full" />
                </DetailField>
                {resource.parent && (
                  <DetailField label="Parent" className="sm:col-span-2">
                    <CopyableId value={resource.parent} className="w-full" />
                  </DetailField>
                )}
                {resource.provider && (
                  <DetailField label="Provider" className="sm:col-span-2">
                    <CopyableId value={resource.provider} className="w-full" />
                  </DetailField>
                )}
              </div>
            </section>
            <PropertySection title="Inputs" values={resource.inputs} />
            <PropertySection title="Outputs" values={resource.outputs} />
            <section className="flex flex-col gap-3">
              <SectionHeading title="Relationships" count={references.length} />
              {references.length ? (
                references.map(({ label, urn }, index) => (
                  <div
                    key={`${label}-${urn}-${index}`}
                    className="grid min-w-0 gap-2 rounded-md border p-3 text-sm sm:grid-cols-[auto_minmax(0,1fr)]"
                  >
                    <Badge variant="outline" className="w-fit">{label}</Badge>
                    {resourceByUrn.has(urn) ? (
                      <Button
                        variant="link"
                        className="h-auto min-w-0 justify-start overflow-hidden p-0 text-left"
                        onClick={() => onNavigate(urn)}
                      >
                        <span className="truncate font-mono text-xs" title={urn}>{urn}</span>
                      </Button>
                    ) : (
                      <span className="truncate font-mono text-xs text-muted-foreground" title={urn}>
                        {urn}
                      </span>
                    )}
                  </div>
                ))
              ) : (
                <p className="text-sm text-muted-foreground">No dependencies.</p>
              )}
            </section>
          </div>
        </ScrollArea>
      </SheetContent>
    </Sheet>
  );
}

function DetailField({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5", className)}>
      <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </span>
      {children}
    </div>
  );
}

function SectionHeading({ title, count }: { title: string; count?: number }) {
  return (
    <div className="flex items-center gap-2">
      <h3 className="font-semibold">{title}</h3>
      {count !== undefined && <Badge variant="secondary">{count}</Badge>}
    </div>
  );
}

function PropertySection({ title, values }: { title: string; values?: Record<string, PulumiValue> }) {
  const entries = Object.entries(values ?? {});
  return (
    <section className="flex min-w-0 flex-col gap-3">
      <SectionHeading title={title} count={entries.length} />
      {entries.length ? (
        <div className="overflow-hidden rounded-lg border">
          {entries.map(([key, value], index) => (
            <div key={key} className="grid min-w-0 gap-2 p-3 text-sm sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)] sm:gap-4">
              <span className="truncate font-medium" title={key}>{key}</span>
              <div className="min-w-0"><PropertyValue value={value} /></div>
              {index < entries.length - 1 && <Separator className="sm:col-span-2" />}
            </div>
          ))}
        </div>
      ) : <p className="text-sm text-muted-foreground">None.</p>}
    </section>
  );
}

function PropertyValue({ value }: { value: PulumiValue }) {
  if (isPulumiSecret(value)) return <SecretBadge />;
  if (value !== null && typeof value === "object") {
    return (
      <pre className="max-h-56 w-full overflow-auto whitespace-pre rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
        <code>{JSON.stringify(maskSecrets(value), null, 2)}</code>
      </pre>
    );
  }
  const rendered = value === null ? "null" : String(value);
  if (rendered.length > 80 || rendered.includes("\n")) {
    return (
      <pre className="max-h-32 w-full overflow-auto whitespace-pre-wrap break-words rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
        <code>{rendered}</code>
      </pre>
    );
  }
  return <code className="block truncate font-mono text-xs" title={rendered}>{rendered}</code>;
}

function StackDetailSkeleton() {
  return <div className="flex flex-col gap-6"><Skeleton className="h-20 w-full" /><Skeleton className="h-10 w-80" /><Skeleton className="h-96 w-full" /></div>;
}

export function resourceName(resource: PulumiResource) {
  const parts = resource.urn.split("::");
  return parts[parts.length - 1] ?? resource.urn;
}

export function resourceType(resource: PulumiResource) {
  const parts = resource.urn.split("::");
  return resource.type ?? parts[parts.length - 2] ?? "unknown";
}

export function stackOutputs(resources: PulumiResource[]): Record<string, PulumiValue> {
  return resources.find((resource) => resourceType(resource) === "pulumi:pulumi:Stack")?.outputs ?? {};
}

export function serializeOutput(value: PulumiValue): string {
  return value !== null && typeof value === "object" ? JSON.stringify(value) : String(value);
}

function resourceProvider(resource: PulumiResource) {
  return resourceType(resource).split(":")[0] || "-";
}

function providerUrn(provider: string) {
  return provider.replace(/::[^:]+$/, "");
}

export function containsSecret(value: unknown): boolean {
  if (isPulumiSecret(value)) return true;
  if (Array.isArray(value)) return value.some(containsSecret);
  if (value && typeof value === "object") return Object.values(value).some(containsSecret);
  return false;
}

export function maskSecrets(value: unknown): unknown {
  if (isPulumiSecret(value)) return "[secret]";
  if (Array.isArray(value)) return value.map(maskSecrets);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, maskSecrets(child)]));
  }
  return value;
}
