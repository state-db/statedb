import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  ChevronDown,
  ChevronRight,
  Download,
  List,
  Lock,
  Network,
  Search,
} from "lucide-react";
import { useMemo, useState } from "react";

import { CopyableId } from "@/components/console/CopyableId";
import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { isPulumiSecret, SecretBadge } from "@/components/console/SecretBadge";
import { StatusBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
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
import { api, NotFoundError, type PulumiResource, type PulumiValue } from "@/lib/api";
import { cn } from "@/lib/utils";

type ViewMode = "flat" | "tree";

export function StackDetailPage({
  org,
  project,
  stack,
}: {
  org: string;
  project: string;
  stack: string;
}) {
  const query = useQuery({
    queryKey: ["workspace", org, project, stack],
    queryFn: () => api.workspaces.get(org, project, stack),
    refetchInterval: (current) =>
      current.state.data?.lastUpdate?.status === "in-progress" ? 3_000 : false,
  });
  const [filter, setFilter] = useState("");
  const [selectedUrn, setSelectedUrn] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    const saved = window.localStorage.getItem("statedb.resources.view");
    return saved === "tree" ? "tree" : "flat";
  });

  const resources = query.data?.resources ?? [];
  const visible = useMemo(() => {
    const needle = filter.trim().toLocaleLowerCase();
    return resources
      .filter((resource) =>
        `${resourceType(resource)} ${resourceName(resource)}`
          .toLocaleLowerCase()
          .includes(needle),
      )
      .sort((a, b) =>
        `${resourceType(a)}\0${resourceName(a)}`.localeCompare(
          `${resourceType(b)}\0${resourceName(b)}`,
        ),
      );
  }, [filter, resources]);
  const selected = resources.find((resource) => resource.urn === selectedUrn) ?? null;

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
          <Button variant="outline" asChild>
            <a href={api.workspaces.exportUrl(org, project, stack)} download>
              <Download data-icon="inline-start" />
              Export
            </a>
          </Button>
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

      <Tabs value="resources">
        <TabsList>
          <TabsTrigger value="resources">Resources</TabsTrigger>
          <TabsTrigger value="outputs" disabled>Outputs</TabsTrigger>
          <TabsTrigger value="activity" disabled>Activity</TabsTrigger>
          <TabsTrigger value="settings" disabled>Settings</TabsTrigger>
        </TabsList>
      </Tabs>

      <section className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="relative w-full max-w-sm">
            <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <Input
              className="pl-9"
              placeholder="Filter by type or name…"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
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
          <ResourceTable resources={visible} onSelect={setSelectedUrn} />
        ) : (
          <ResourceTree resources={visible} allResources={resources} onSelect={setSelectedUrn} />
        )}
      </section>

      <ResourceSheet
        resource={selected}
        resources={resources}
        onOpenChange={(open) => !open && setSelectedUrn(null)}
        onNavigate={setSelectedUrn}
      />
    </div>
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
  onSelect,
}: {
  resources: PulumiResource[];
  allResources: PulumiResource[];
  onSelect: (urn: string) => void;
}) {
  const visibleUrns = new Set(resources.map((resource) => resource.urn));
  const children = new Map<string, PulumiResource[]>();
  for (const resource of resources) {
    if (resource.parent && visibleUrns.has(resource.parent)) {
      children.set(resource.parent, [...(children.get(resource.parent) ?? []), resource]);
    }
  }
  const roots = resources.filter(
    (resource) => !resource.parent || !visibleUrns.has(resource.parent),
  );
  const allChildren = new Map<string, number>();
  for (const resource of allResources) {
    if (resource.parent) allChildren.set(resource.parent, (allChildren.get(resource.parent) ?? 0) + 1);
  }

  return (
    <div className="rounded-md border">
      <Table className="min-w-[900px] table-fixed">
        <ResourceTableHeader />
        <TableBody>
          {roots.map((resource) => (
            <TreeBranch
              key={resource.urn}
              resource={resource}
              children={children}
              childCounts={allChildren}
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
  depth,
  onSelect,
}: {
  resource: PulumiResource;
  children: Map<string, PulumiResource[]>;
  childCounts: Map<string, number>;
  depth: number;
  onSelect: (urn: string) => void;
}) {
  const descendants = children.get(resource.urn) ?? [];
  const [open, setOpen] = useState(true);
  return (
    <>
      <ResourceRow
        resource={resource}
        depth={depth}
        childCount={childCounts.get(resource.urn) ?? 0}
        open={open}
        onToggle={descendants.length ? () => setOpen((value) => !value) : undefined}
        onSelect={onSelect}
      />
      {open && descendants.map((child) => (
        <TreeBranch
          key={child.urn}
          resource={child}
          children={children}
          childCounts={childCounts}
          depth={depth + 1}
          onSelect={onSelect}
        />
      ))}
    </>
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

function ResourceRow({
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
      <TableCell className="max-w-0">
        <div className="flex min-w-0 items-center gap-1" style={{ paddingLeft: `${depth * 1.25}rem` }}>
          {onToggle ? (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={(event) => {
                event.stopPropagation();
                onToggle();
              }}
              aria-label={open ? "Collapse children" : "Expand children"}
            >
              {open ? <ChevronDown /> : <ChevronRight />}
            </Button>
          ) : depth > 0 ? (
            <span className="w-8" />
          ) : null}
          <span className="truncate font-mono text-xs" title={resourceType(resource)}>
            {resourceType(resource)}
          </span>
          {childCount > 0 && <span className="text-xs text-muted-foreground">({childCount})</span>}
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
}

function ResourceSheet({
  resource,
  resources,
  onOpenChange,
  onNavigate,
}: {
  resource: PulumiResource | null;
  resources: PulumiResource[];
  onOpenChange: (open: boolean) => void;
  onNavigate: (urn: string) => void;
}) {
  if (!resource) return null;
  const knownUrns = new Set(resources.map((candidate) => candidate.urn));
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
                    {knownUrns.has(urn) ? (
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
