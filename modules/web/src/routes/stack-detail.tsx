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
  type ActivityActor,
  type PulumiResource,
  type PulumiValue,
  type StackDetail,
  type UpdateHistoryRow,
  type WorkspaceLastUpdate,
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
        </div>
        {updateInProgress && detail.lastUpdate && (
          <Alert>
            <Network />
            <AlertTitle>{activeRunTitle(detail.lastUpdate)}</AlertTitle>
            <AlertDescription>
              {activeRunActorLabel(detail.lastUpdate) ? (
                <>
                  <span className="font-medium">{activeRunActorLabel(detail.lastUpdate)}</span>
                  {" started this run "}
                </>
              ) : (
                <>Started </>
              )}
              <Timestamp value={detail.lastUpdate.startedAt} />. Resources shown are from v
              {detail.version}.{" "}
              {detail.lastUpdate.version === null ? (
                <Link
                  className="underline underline-offset-4"
                  to="/console/$org/$project/$stack/previews/$updateId"
                  params={{ org, project, stack, updateId: detail.lastUpdate.id }}
                >
                  View live run
                </Link>
              ) : (
                <Link
                  className="underline underline-offset-4"
                  to="/console/$org/$project/$stack/updates/$version"
                  params={{ org, project, stack, version: String(detail.lastUpdate.version) }}
                >
                  View live run
                </Link>
              )}
            </AlertDescription>
          </Alert>
        )}
        <StackInsightStrip detail={detail} resources={resources} outputs={outputs} />
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
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm text-muted-foreground">
                Showing {visible.length.toLocaleString()} of {resources.length.toLocaleString()}{" "}
                {pluralize("resource", resources.length)}
              </span>
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

function StackInsightStrip({
  detail,
  resources,
  outputs,
}: {
  detail: StackDetail;
  resources: PulumiResource[];
  outputs: Record<string, PulumiValue>;
}) {
  const outputCount = Object.keys(outputs).length;
  const lastUpdate = detail.lastUpdate;
  const providerCount = resourceProviders(resources);
  const secretCount = secretOutputCount(outputs);
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/20 px-3 py-2 text-sm">
      <Badge variant="outline">
        {providerCount.toLocaleString()} {pluralize("provider", providerCount)}
      </Badge>
      <Badge variant="outline">
        {outputCount.toLocaleString()} {pluralize("output", outputCount)}
      </Badge>
      {secretCount > 0 && (
        <Badge variant="warning">
          {secretCount.toLocaleString()} secret {pluralize("output", secretCount)}
        </Badge>
      )}
      {lastUpdate ? (
        <div className="flex flex-wrap items-center gap-2 text-muted-foreground">
          <span>{lastRunLabel(lastUpdate)}</span>
          {lastUpdate.actor && <span>by {lastUpdateActorLabel(lastUpdate.actor)}</span>}
          <KindBadge kind={lastUpdate.kind} />
          <StatusBadge status={lastUpdate.status} />
        </div>
      ) : (
        <span className="text-muted-foreground">No runs yet</span>
      )}
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
            <p className="text-sm">
              Active since <Timestamp value={detail.lock.lockedAt} />
              {detail.lastUpdate?.actor && (
                <> · Started by <span className="font-medium">{lastUpdateActorLabel(detail.lastUpdate.actor)}</span></>
              )}
              .
            </p>
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
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Detail label="Base snapshot version">v{detail.baseSnapshotVersion}</Detail>
          <Detail label="Journal entries">{detail.journalEntriesSinceCompaction}</Detail>
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
      { id: "actor", header: "Actor", cell: ({ row }) => <ActivityActorCell actor={row.original.actor} /> },
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

export function lastUpdateActorLabel(actor: NonNullable<WorkspaceLastUpdate["actor"]>): string {
  const label = actor.displayName ?? actor.email ?? actor.fallback;
  return actor.revoked ? `${label} (revoked)` : label;
}

export function lastRunLabel(update: WorkspaceLastUpdate): string {
  return update.version === null ? "Last run Preview" : `Last run v${update.version}`;
}

export function activeRunTitle(update: WorkspaceLastUpdate): string {
  return update.version === null ? "Preview in progress" : `Update v${update.version} in progress`;
}

export function activeRunActorLabel(update: WorkspaceLastUpdate): string | null {
  return update.actor ? lastUpdateActorLabel(update.actor) : null;
}

function ActivityActorCell({ actor }: { actor: ActivityActor | null }) {
  if (!actor) return <span className="text-muted-foreground">Unknown</span>;
  const label = lastUpdateActorLabel(actor);
  return (
    <Tooltip>
      <TooltipTrigger asChild><span>{label}</span></TooltipTrigger>
      <TooltipContent>
        <div className="flex flex-col gap-1">
          {actor.principalId && <span>Principal: {actor.principalId}</span>}
          {actor.credentialId && <span>Credential: {actor.credentialId}</span>}
          {actor.accessProfile && <span>Access: {actor.accessProfile}</span>}
        </div>
      </TooltipContent>
    </Tooltip>
  );
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
        <div className="rounded-md border bg-card">
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
          <pre className="scroll-thin mt-2 max-h-80 overflow-auto whitespace-pre rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
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

function CopyButton({ value, label, className }: { value: string; label: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      toast("Could not copy to clipboard");
      return;
    }
    setCopied(true);
    toast("Copied");
    window.setTimeout(() => setCopied(false), 1200);
  }
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className={cn("shrink-0", className)}
      onClick={() => void copy()}
      aria-label={label}
    >
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
    <>
      <ResourceCards resources={resources} onSelect={onSelect} />
      <div className="hidden rounded-md border bg-card md:block">
        <Table className="min-w-[900px] table-fixed">
          <ResourceTableHeader />
          <TableBody>
            {resources.map((resource) => (
              <ResourceRow key={resource.urn} resource={resource} onSelect={onSelect} />
            ))}
          </TableBody>
        </Table>
      </div>
    </>
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
    <>
      <ResourceCards resources={treeResourcesForCards(model.roots, model.children)} onSelect={onSelect} />
      <div className="hidden rounded-md border bg-card md:block">
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
    </>
  );
}

function ResourceCards({
  resources,
  onSelect,
}: {
  resources: PulumiResource[];
  onSelect: (urn: string) => void;
}) {
  return (
    <div className="flex flex-col gap-2 md:hidden">
      {resources.map((resource) => (
        <Card
          key={resource.urn}
          className="rounded-lg shadow-none"
          role="button"
          tabIndex={0}
          onClick={() => onSelect(resource.urn)}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              onSelect(resource.urn);
            }
          }}
        >
          <CardHeader className="gap-1 pb-2">
            <div className="flex min-w-0 items-start justify-between gap-3">
              <div className="min-w-0">
                <CardTitle className="truncate text-base" title={resourceName(resource)}>
                  {resourceName(resource)}
                </CardTitle>
                <CardDescription className="truncate font-mono" title={resourceType(resource)}>
                  {resourceType(resource)}
                </CardDescription>
              </div>
              <Badge variant="secondary" className="shrink-0">
                {resource.custom === false ? "component" : "custom"}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="grid gap-2 text-sm sm:grid-cols-3">
            <MobileResourceFact label="Provider" value={resourceProvider(resource)} />
            <MobileResourceFact
              label="Created"
              value={resource.created ? <Timestamp value={resource.created} /> : "-"}
            />
            <MobileResourceFact
              label="Modified"
              value={resource.modified ? <Timestamp value={resource.modified} /> : "-"}
            />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

function MobileResourceFact({
  label,
  value,
}: {
  label: string;
  value: React.ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <span className="min-w-0 truncate">{value}</span>
    </div>
  );
}

function treeResourcesForCards(
  roots: PulumiResource[],
  children: Map<string, PulumiResource[]>,
) {
  const ordered: PulumiResource[] = [];
  const visit = (resource: PulumiResource) => {
    ordered.push(resource);
    for (const child of children.get(resource.urn) ?? []) visit(child);
  };
  for (const root of roots) visit(root);
  return ordered;
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
      <SheetContent className="flex w-full flex-col gap-0 overflow-hidden p-0 sm:max-w-[min(1040px,90vw)]">
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
            <PropertiesPanel key={resource.urn} inputs={resource.inputs} outputs={resource.outputs} />
            <section className="flex flex-col gap-3">
              <SectionHeading title="Relationships" count={references.length} />
              {references.length ? (
                references.map(({ label, urn }, index) => (
                  <div
                    key={`${label}-${urn}-${index}`}
                    className="grid min-w-0 gap-2 rounded-md border p-3 text-sm sm:grid-cols-[auto_minmax(0,1fr)_auto] sm:items-center"
                  >
                    <Badge variant="outline" className="w-fit">{label}</Badge>
                    {resourceByUrn.has(urn) ? (
                      <Button
                        variant="link"
                        className="h-auto min-w-0 justify-start overflow-hidden p-0 text-left"
                        onClick={() => onNavigate(urn)}
                      >
                        <span className="truncate text-left font-mono text-xs" dir="rtl" title={urn}>{urn}</span>
                      </Button>
                    ) : (
                      <span
                        className="truncate text-left font-mono text-xs text-muted-foreground"
                        dir="rtl"
                        title={urn}
                      >
                        {urn}
                      </span>
                    )}
                    <CopyButton value={urn} label="Copy URN" className="size-6 [&_svg]:size-3.5" />
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

const PROPERTY_SEARCH_THRESHOLD = 8;

// Double-underscore keys (__internal, __pulumi_raw_state_*, ...) are
// provider/engine bookkeeping a user never configured and rarely needs —
// they're kept out of the main list and filter, tucked behind a closed
// disclosure instead of competing with real resource state for attention.
function isInternalPropertyKey(key: string): boolean {
  return key.startsWith("__");
}

function PropertiesPanel({
  inputs,
  outputs,
}: {
  inputs?: Record<string, PulumiValue>;
  outputs?: Record<string, PulumiValue>;
}) {
  const [query, setQuery] = useState("");
  const rows = useMemo(() => buildPropertyRows(inputs, outputs), [inputs, outputs]);
  const regularRows = useMemo(() => rows.filter((row) => !isInternalPropertyKey(row.key)), [rows]);
  const internalRows = useMemo(() => rows.filter((row) => isInternalPropertyKey(row.key)), [rows]);
  const needle = query.trim().toLowerCase();
  const visible = needle
    ? regularRows.filter(
        (row) =>
          row.key.toLowerCase().includes(needle) ||
          serializeOutput(maskSecrets(row.value) as PulumiValue).toLowerCase().includes(needle),
      )
    : regularRows;

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <SectionHeading title="Properties" count={regularRows.length} />
        {regularRows.length > PROPERTY_SEARCH_THRESHOLD && (
          <div className="relative w-48">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="h-7 pl-7 text-xs"
              placeholder="Filter properties…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              aria-label="Filter properties"
            />
          </div>
        )}
      </div>
      {regularRows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No properties.</p>
      ) : visible.length === 0 ? (
        <p className="text-sm text-muted-foreground">No properties match &ldquo;{query}&rdquo;.</p>
      ) : (
        <PropertyRowTable rows={visible} />
      )}
      {internalRows.length > 0 && (
        <Collapsible>
          <CollapsibleTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="w-fit text-muted-foreground"
            >
              <ChevronRight data-icon="inline-start" />
              {internalRows.length} internal {pluralize("property", internalRows.length)}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <PropertyRowTable rows={internalRows} className="mt-2" />
          </CollapsibleContent>
        </Collapsible>
      )}
    </section>
  );
}

function PropertyRowTable({ rows, className }: { rows: MergedPropertyRow[]; className?: string }) {
  return (
    <div className={cn("overflow-hidden rounded-lg border bg-card", className)}>
      {rows.map((row, index) => (
        <div
          key={row.key}
          className="grid min-w-0 gap-2 p-3 text-sm sm:grid-cols-[minmax(0,10rem)_minmax(0,1fr)] sm:gap-4"
        >
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span className="truncate font-medium" title={row.key}>{row.key}</span>
            <PropertySourceBadge row={row} />
          </div>
          <div className="min-w-0"><PropertyValueCell value={row.value} /></div>
          {index < rows.length - 1 && <Separator className="sm:col-span-2" />}
        </div>
      ))}
    </div>
  );
}

function PropertySourceBadge({ row }: { row: MergedPropertyRow }) {
  if (row.source === "output") {
    return (
      <Badge variant="secondary" className="shrink-0 text-[10px]">
        output
      </Badge>
    );
  }
  if (row.source === "input") {
    return (
      <Badge variant="outline" className="shrink-0 text-[10px]">
        input
      </Badge>
    );
  }
  if (row.differs) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <Badge variant="warning" className="shrink-0 text-[10px]">
            differs from input
          </Badge>
        </TooltipTrigger>
        <TooltipContent className="max-w-sm">
          Configured as{" "}
          <code className="font-mono">
            {serializeOutput(maskSecrets(row.inputValue ?? null) as PulumiValue)}
          </code>
        </TooltipContent>
      </Tooltip>
    );
  }
  return null;
}

function PropertyValueCell({ value }: { value: PulumiValue }) {
  if (isPulumiSecret(value)) return <SecretBadge />;
  if (value !== null && typeof value === "object") {
    const masked = maskSecrets(value) as PulumiValue;
    return (
      <div className="flex min-w-0 items-start gap-1">
        <pre className="scroll-thin max-h-56 min-w-0 flex-1 overflow-auto whitespace-pre rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
          <code>{JSON.stringify(masked, null, 2)}</code>
        </pre>
        <CopyButton value={serializeOutput(masked)} label="Copy value" className="size-6 [&_svg]:size-3.5" />
      </div>
    );
  }
  const rendered = value === null ? "null" : String(value);
  if (rendered.length > 80 || rendered.includes("\n")) {
    return (
      <div className="flex min-w-0 items-start gap-1">
        <pre className="scroll-thin max-h-32 min-w-0 flex-1 overflow-auto whitespace-pre-wrap break-words rounded-md border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
          <code>{rendered}</code>
        </pre>
        <CopyButton value={rendered} label="Copy value" className="size-6 [&_svg]:size-3.5" />
      </div>
    );
  }
  return <CopyableId value={rendered} buttonClassName="size-6 [&_svg]:size-3.5" />;
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

export type PropertySource = "both" | "input" | "output";

export interface MergedPropertyRow {
  key: string;
  value: PulumiValue;
  source: PropertySource;
  differs: boolean;
  inputValue?: PulumiValue;
  outputValue?: PulumiValue;
}

// Fixed lookup order for keys every resource-shaped API (AWS, GCP, Azure,
// Kubernetes, ...) tends to surface first. Anything not in this list falls
// back to alphabetical, so the same resource always renders its properties
// in the same order.
const PROPERTY_PRIORITY = [
  "id",
  "arn",
  "name",
  "displayname",
  "hostname",
  "endpoint",
  "url",
  "domainname",
  "bucket",
  "region",
  "zone",
  "status",
  "state",
];

export function propertyPriorityRank(key: string): number {
  // Double-underscore keys (__internal, __pulumi_raw_state_*, ...) are
  // provider/engine bookkeeping, not resource state a user asked for. They
  // sort dead last regardless of where they'd otherwise fall alphabetically,
  // instead of floating to the top the way "_" does in ASCII/collator order.
  if (key.startsWith("__")) return PROPERTY_PRIORITY.length + 1;
  const rank = PROPERTY_PRIORITY.indexOf(key.toLowerCase());
  return rank === -1 ? PROPERTY_PRIORITY.length : rank;
}

// Providers routinely round-trip a passthrough value (e.g. a `keepers` map)
// through their own schema, coercing an authored number/boolean to its
// string form without any semantic change (2 -> "2"). A byte-exact compare
// would flag every one of those as "differs from input", drowning out the
// rare case that's actually worth a look. Compare structurally but treat
// same-representation primitives of different JS types as equal.
export function propertyValuesEquivalent(a: PulumiValue, b: PulumiValue): boolean {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((value, index) => propertyValuesEquivalent(value, b[index]));
  }
  if (Array.isArray(a) || Array.isArray(b)) return false;
  if (a !== null && b !== null && typeof a === "object" && typeof b === "object") {
    const aKeys = Object.keys(a);
    const bKeys = Object.keys(b);
    return (
      aKeys.length === bKeys.length &&
      aKeys.every((key) => Object.prototype.hasOwnProperty.call(b, key) && propertyValuesEquivalent(a[key], b[key]))
    );
  }
  if (a === null || b === null || typeof a === "object" || typeof b === "object") return false;
  return String(a) === String(b);
}

// Pulumi's convention copies every input into the resource's outputs plus
// whatever the provider computes, so inputs and outputs are two overlapping
// views of the same state rather than independent lists. Merging them here
// (instead of rendering "Inputs" and "Outputs" as separate sections) removes
// that duplication and surfaces only the cases worth a second look: values
// the provider computed (source: "output"), values the provider never
// echoed back (source: "input"), and values where the deployed output
// disagrees with what was configured (differs: true).
export function buildPropertyRows(
  inputs: Record<string, PulumiValue> | undefined,
  outputs: Record<string, PulumiValue> | undefined,
): MergedPropertyRow[] {
  const inputEntries = inputs ?? {};
  const outputEntries = outputs ?? {};
  const keys = new Set([...Object.keys(inputEntries), ...Object.keys(outputEntries)]);

  const rows = Array.from(keys).map((key): MergedPropertyRow => {
    const hasInput = Object.prototype.hasOwnProperty.call(inputEntries, key);
    const hasOutput = Object.prototype.hasOwnProperty.call(outputEntries, key);
    const inputValue = inputEntries[key];
    const outputValue = outputEntries[key];
    const source: PropertySource = hasInput && hasOutput ? "both" : hasOutput ? "output" : "input";
    return {
      key,
      value: hasOutput ? outputValue : inputValue,
      source,
      differs: source === "both" && !propertyValuesEquivalent(inputValue, outputValue),
      inputValue: hasInput ? inputValue : undefined,
      outputValue: hasOutput ? outputValue : undefined,
    };
  });

  return rows.sort((a, b) => {
    const rankDiff = propertyPriorityRank(a.key) - propertyPriorityRank(b.key);
    return rankDiff !== 0 ? rankDiff : RESOURCE_COLLATOR.compare(a.key, b.key);
  });
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

function resourceProviders(resources: PulumiResource[]) {
  return new Set(resources.map(resourceProvider)).size;
}

function secretOutputCount(outputs: Record<string, PulumiValue>) {
  return Object.values(outputs).filter(containsSecret).length;
}

function pluralize(noun: string, count: number) {
  return count === 1 ? noun : `${noun}s`;
}
