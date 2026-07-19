import { useQuery } from "@tanstack/react-query";
import {
  ArrowDown,
  ChevronDown,
  CircleAlert,
  EyeOff,
  GitBranch,
  Monitor,
  Settings2,
} from "lucide-react";
import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";

import { EmptyState } from "@/components/console/EmptyState";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { StatusBadge } from "@/components/console/StatusBadge";
import { Timestamp } from "@/components/console/Timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge, type BadgeProps } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { api, NotFoundError, type RunDetail, type RunEvent } from "@/lib/api";
import { cn } from "@/lib/utils";

type RunLocator = { version: number } | { previewId: string };
type ResourceChange = { sequence: number; op: string; type: string; name: string; detail: unknown };
type ResourceDisplayRow = ResourceChange & {
  urn: string;
  parent: string;
  marker: string;
  status: string;
  info: string;
  done: boolean;
  failed: boolean;
};
type PropertyChange = { path: string; kind: string; oldValue: unknown; newValue: unknown };

export function RunDetailPage({
  org,
  project,
  stack,
  locator,
}: {
  org: string;
  project: string;
  stack: string;
  locator: RunLocator;
}) {
  const query = useQuery({
    queryKey: ["run", org, project, stack, locator],
    queryFn: ({ signal }) => api.workspaces.run(org, project, stack, locator, signal),
    refetchInterval: (current) => current.state.data?.status === "in-progress" ? 3_000 : false,
  });
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [expired, setExpired] = useState(false);
  const [eventsError, setEventsError] = useState<unknown>(null);
  const previousStatus = useRef<string | null>(null);
  const eventCursor = useRef(-1);

  useEffect(() => {
    const status = query.data?.status;
    if (previousStatus.current === "in-progress" && status && status !== "in-progress") {
      toast(`Update finished: ${status}`);
    }
    if (status) previousStatus.current = status;
  }, [query.data?.status]);

  useEffect(() => {
    setEvents([]);
    setExpired(false);
    eventCursor.current = -1;
  }, [query.data?.id]);

  useEffect(() => {
    const run = query.data;
    if (!run) return;
    const controller = new AbortController();
    let stopped = false;

    const follow = async () => {
      try {
        let nextAfter: number | null = eventCursor.current;
        do {
          const page = await api.workspaces.runEvents(
            org, project, stack, run.id, nextAfter, controller.signal,
          );
          if (stopped) return;
          if (page.events.length) {
            setEvents((current) => mergeEvents(current, page.events));
            eventCursor.current = page.events[page.events.length - 1]?.sequence ?? eventCursor.current;
          }
          setExpired(page.expired);
          nextAfter = page.nextAfter;
        } while (nextAfter !== null);
        setEventsError(null);
      } catch (error) {
        if (!controller.signal.aborted) setEventsError(error);
      }
    };

    void follow();
    const interval = run.status === "in-progress" ? window.setInterval(follow, 3_000) : undefined;
    return () => {
      stopped = true;
      controller.abort();
      if (interval) window.clearInterval(interval);
    };
  }, [query.data?.id, query.data?.status, org, project, stack]);

  if (query.isLoading) return <RunDetailSkeleton />;
  if (query.error instanceof NotFoundError) {
    const title = "version" in locator
      ? `No update v${locator.version} on this stack`
      : "Preview not found";
    return <EmptyState title={title} description="The run does not exist or you do not have access to it." />;
  }
  if (query.error || !query.data) {
    return <ErrorAlert title="Could not load run" error={query.error} onRetry={() => void query.refetch()} />;
  }

  return (
    <RunView
      org={org}
      project={project}
      stack={stack}
      run={query.data}
      events={events}
      expired={expired}
      eventsError={eventsError}
    />
  );
}

function RunView({
  org, project, stack, run, events, expired, eventsError,
}: {
  org: string; project: string; stack: string; run: RunDetail; events: RunEvent[];
  expired: boolean; eventsError: unknown;
}) {
  const summary = changeSummary(run);
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <h1 className="text-2xl font-semibold tracking-tight">
              {org} / {project} / {stack}
              <span className="font-normal text-muted-foreground"> · {runTitle(run)}</span>
            </h1>
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              {run.message && <span>“{run.message}”</span>}
              <span>started <Timestamp value={run.startedAt} /></span>
              {run.pulumiVersion && <><span aria-hidden="true">·</span><span>Pulumi {run.pulumiVersion}</span></>}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <StatusBadge status={run.status} />
            <span className="text-sm text-muted-foreground">· {formatRunDuration(run.startedAt, run.endedAt)}</span>
          </div>
        </div>
        <Separator />
        <div className="flex flex-wrap gap-2">
          <Badge variant="success">+{summary.created} created</Badge>
          <Badge variant="warning">~{summary.updated} updated</Badge>
          <Badge variant="destructive">−{summary.deleted} deleted</Badge>
          <Badge variant="muted">{summary.unchanged} unchanged</Badge>
        </div>
      </header>

      <Tabs defaultValue="log">
        <TabsList>
          <TabsTrigger value="log">Log</TabsTrigger>
          <TabsTrigger value="changes">Changes</TabsTrigger>
          <TabsTrigger value="configuration">Configuration</TabsTrigger>
        </TabsList>
        <TabsContent value="log" className="mt-4">
          <RunLog events={events} expired={expired} error={eventsError} live={run.status === "in-progress"} preview={run.version === null} />
        </TabsContent>
        <TabsContent value="changes" className="mt-4">
          <RunChanges events={events} collapseReplacements={run.version === null || run.status !== "in-progress"} />
        </TabsContent>
        <TabsContent value="configuration" className="mt-4">
          <Configuration run={run} events={events} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function RunLog({ events, expired, error, live, preview }: { events: RunEvent[]; expired: boolean; error: unknown; live: boolean; preview: boolean }) {
  const [filter, setFilter] = useState("all");
  const viewport = useRef<HTMLDivElement>(null);
  const rows = useMemo(() => resourceRows(events, preview), [events, preview]);
  const messages = events.filter((event) => {
    if (event.kind === "stdout" || event.kind === "error") return true;
    if (event.kind !== "diagnostic") return false;
    const body = isRecord(event.payload.diagnosticEvent) ? event.payload.diagnosticEvent : {};
    return body.ephemeral !== true && event.severity !== "debug";
  });
  const visibleMessages = filter === "diagnostics" ? messages.filter((event) => event.kind === "diagnostic" || event.kind === "error") : messages;
  const summary = [...events].reverse().find((event) => event.kind === "summary");
  const jumpToBottom = () => viewport.current?.scrollIntoView({ block: "end" });

  useEffect(() => {
    if (live) jumpToBottom();
  }, [events.length, live]);

  if (expired) {
    return <Alert><CircleAlert /><AlertTitle>Event log unavailable</AlertTitle><AlertDescription>Event log expired per retention policy.</AlertDescription></Alert>;
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <ToggleGroup type="single" variant="outline" size="sm" value={filter} onValueChange={(value) => value && setFilter(value)}>
          <ToggleGroupItem value="all">All</ToggleGroupItem>
          <ToggleGroupItem value="diagnostics">Diagnostics only</ToggleGroupItem>
        </ToggleGroup>
        <Button variant="outline" size="sm" onClick={jumpToBottom}><ArrowDown data-icon="inline-start" />Jump to bottom</Button>
      </div>
      {error !== null && <ErrorAlert title="Live log disconnected" error={error} />}
      <ScrollArea className="h-[32rem] rounded-lg border bg-zinc-950 text-zinc-100">
        <div className="flex min-w-[42rem] flex-col p-4 font-mono text-xs leading-5">
          {filter === "all" && rows.length > 0 && (
            <div className="mb-4 grid grid-cols-[2rem_minmax(12rem,1fr)_minmax(10rem,1fr)_minmax(9rem,auto)] gap-2 border-b border-zinc-800 pb-2 text-zinc-500">
              <span />
              <span>Type</span>
              <span>Name</span>
              <span>{preview ? "Plan" : "Status"}</span>
            </div>
          )}
          {filter === "all" && rows.map((row) => <ResourceLogRow key={`${row.urn}-${row.op}`} row={row} rows={rows} />)}
          {visibleMessages.length > 0 && (
            <section className={cn("flex flex-col", filter === "all" && rows.length > 0 && "mt-5 border-t border-zinc-800 pt-4")}>
              {filter === "all" && <h3 className="mb-2 font-sans text-xs font-medium uppercase tracking-wide text-zinc-500">Diagnostics and output</h3>}
              <DiagnosticOutput events={visibleMessages} rows={rows} preview={preview} />
            </section>
          )}
          {filter === "all" && summary && <SummaryLogLine event={summary} preview={preview} />}
          {rows.length === 0 && visibleMessages.length === 0 && !summary && <p className="text-zinc-500">Waiting for events…</p>}
          <div ref={viewport} />
        </div>
      </ScrollArea>
    </div>
  );
}

function DiagnosticOutput({ events, rows, preview }: { events: RunEvent[]; rows: ResourceDisplayRow[]; preview: boolean }) {
  const stdout = events.filter((event) => event.kind === "stdout");
  const diagnostics = events.filter((event) => event.kind !== "stdout");
  const groups = new Map<string, RunEvent[]>();
  for (const event of diagnostics) {
    const body = event.kind === "diagnostic" && isRecord(event.payload.diagnosticEvent) ? event.payload.diagnosticEvent : {};
    const urn = String(body.urn ?? "");
    groups.set(urn, [...(groups.get(urn) ?? []), event]);
  }
  const names = new Map(rows.map((row) => [row.urn, `${row.type} (${row.name})`]));
  return (
    <div className="flex flex-col gap-3">
      {stdout.map((event) => <LogLine key={event.sequence} event={event} preview={preview} />)}
      {[...groups.entries()].map(([urn, grouped]) => (
        <section className="flex flex-col" key={urn || "stack"}>
          <h4 className="mb-1 text-zinc-500">{names.get(urn) ?? "Stack"}:</h4>
          <div className="pl-4">{grouped.map((event) => <LogLine key={event.sequence} event={event} preview={preview} />)}</div>
        </section>
      ))}
    </div>
  );
}

function ResourceLogRow({ row, rows }: { row: ResourceDisplayRow; rows: ResourceDisplayRow[] }) {
  const depth = resourceDepth(row, rows);
  return (
    <div className="grid grid-cols-[2rem_minmax(12rem,1fr)_minmax(10rem,1fr)_minmax(9rem,auto)] gap-2 py-1">
      <span className={cn("font-semibold", markerClass(row.marker))}>{row.failed ? "! " : row.marker}</span>
      <span className="truncate" title={row.type}>{row.type}</span>
      <span className="truncate" style={{ paddingLeft: `${depth * 1.25}rem` }} title={row.name}>{row.name}</span>
      <span className={cn(!row.done && "text-zinc-400", row.failed && "text-red-400")}>{row.status}{!row.done && "…"}{row.info && <span className="text-zinc-500"> · {row.info}</span>}</span>
    </div>
  );
}

function LogLine({ event, preview }: { event: RunEvent; preview: boolean }) {
  const line = formatEventLine(event, preview);
  if (!line) return null;
  return (
    <div className={cn("grid grid-cols-[1.25rem_minmax(0,1fr)] gap-2 py-0.5", diagnosticClass(event))}>
      <span className={cn("select-none font-semibold", markerClass(line.marker))} aria-hidden="true">
        {line.marker}
      </span>
      <span className="whitespace-pre-wrap break-words">{line.text}</span>
    </div>
  );
}

function SummaryLogLine({ event, preview }: { event: RunEvent; preview: boolean }) {
  const line = formatEventLine(event, preview);
  if (!line) return null;
  return <div className="mt-5 border-t border-zinc-800 pt-4"><LogLine event={event} preview={preview} /></div>;
}

function RunChanges({ events, collapseReplacements }: { events: RunEvent[]; collapseReplacements: boolean }) {
  const [showUnchanged, setShowUnchanged] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const changes = useMemo(() => resourceChanges(events, collapseReplacements), [events, collapseReplacements]);
  const visible = showUnchanged ? changes : changes.filter((change) => change.op !== "same");
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2"><Checkbox id="show-unchanged" checked={showUnchanged} onCheckedChange={(value) => setShowUnchanged(value === true)} /><Label htmlFor="show-unchanged">Show unchanged</Label></div>
      <div className="rounded-lg border">
        <Table>
          <TableHeader><TableRow><TableHead>Operation</TableHead><TableHead>Type</TableHead><TableHead>Name</TableHead><TableHead><span className="sr-only">Toggle diff</span></TableHead></TableRow></TableHeader>
          <TableBody>
            {visible.length === 0 ? <TableRow><TableCell colSpan={4} className="h-24 text-center text-muted-foreground">No resource changes recorded.</TableCell></TableRow> : visible.map((change) => {
              const key = `${change.sequence}-${change.name}`;
              const properties = resourcePropertyChanges(change);
              const lineCount = jsonDiffLines(properties).length;
              const autoExpanded = properties.length > 0 && properties.length <= 6 && lineCount <= 12;
              const open = expanded[key] ?? autoExpanded;
              const toggle = () => setExpanded((current) => ({ ...current, [key]: !open }));
              return (
                <Fragment key={key}>
                  <TableRow
                    className="cursor-pointer"
                    tabIndex={0}
                    aria-expanded={open}
                    onClick={toggle}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        toggle();
                      }
                    }}
                  >
                    <TableCell><OperationBadge op={change.op} /></TableCell>
                    <TableCell className="font-mono text-xs">{change.type}</TableCell>
                    <TableCell>{change.name}</TableCell>
                    <TableCell className="text-right text-xs text-muted-foreground">
                      <span className="inline-flex items-center gap-2 whitespace-nowrap">
                        {properties.length > 0 ? `${properties.length} ${properties.length === 1 ? "property" : "properties"}` : "No property details"}
                        <ChevronDown className={cn("transition-transform", open && "rotate-180")} aria-hidden="true" />
                      </span>
                    </TableCell>
                  </TableRow>
                  {open && (
                    <TableRow>
                      <TableCell colSpan={4} className="p-0">
                        <InlineJsonDiff properties={properties} />
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

function resourcePropertyChanges(change: ResourceChange) {
  const envelope = isRecord(change.detail) ? change.detail : {};
  return propertyChanges(isRecord(envelope.metadata) ? envelope.metadata : envelope);
}

function InlineJsonDiff({ properties }: { properties: PropertyChange[] }) {
  if (properties.length === 0) {
    return <div className="p-4"><Alert><CircleAlert /><AlertTitle>No property-level diff</AlertTitle><AlertDescription>Pulumi recorded the resource operation without changed property details.</AlertDescription></Alert></div>;
  }
  return (
    <ScrollArea className="max-h-[30rem] border-y bg-muted/30">
      <pre className="min-w-max py-3 font-mono text-xs leading-5">
        {jsonDiffLines(properties).map((line, index) => (
          <span
            className={cn(
              "grid grid-cols-[2rem_minmax(0,1fr)] whitespace-pre px-4",
              line.kind === "delete" && "bg-destructive/10 text-destructive",
              line.kind === "add" && "bg-primary/5 text-foreground",
              line.kind === "context" && "mt-2 text-muted-foreground first:mt-0",
            )}
            key={`${index}-${line.text}`}
          >
            <span className="select-none" aria-hidden="true">{line.kind === "delete" ? "−" : line.kind === "add" ? "+" : ""}</span>
            <span>{line.text}</span>
          </span>
        ))}
      </pre>
    </ScrollArea>
  );
}

function Configuration({ run, events }: { run: RunDetail; events: RunEvent[] }) {
  const outputs = stackOutputsFromEvents(events);
  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <KeyValueCard
        icon={Settings2}
        title="Configuration"
        description="Stack configuration captured for this run."
        values={maskConfig(run.config)}
      />
      <KeyValueCard
        icon={Settings2}
        title="Update options"
        description="Engine options used for this operation."
        values={run.options}
      />
      <EnvironmentMetadata metadata={run.metadata} />
      {Object.keys(outputs).length > 0 && (
        <KeyValueCard
          icon={Settings2}
          title="Stack outputs"
          description="Final outputs captured from the stack resource."
          values={outputs}
        />
      )}
    </div>
  );
}

function KeyValueCard({
  icon: Icon,
  title,
  description,
  values,
}: {
  icon: typeof Settings2;
  title: string;
  description: string;
  values: Record<string, unknown>;
}) {
  const entries = Object.entries(values);
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <Icon />
          <CardTitle>{title}</CardTitle>
        </div>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableBody>
            {entries.length === 0 ? (
              <TableRow><TableCell className="text-muted-foreground">None recorded</TableCell></TableRow>
            ) : entries.map(([key, value]) => (
              <TableRow key={key}>
                <TableCell className="w-2/5 align-top font-mono text-xs">{key.includes(":") ? key : friendlyKey(key)}</TableCell>
                <TableCell className="max-w-0 break-words text-right font-mono text-xs">
                  <PresentedValue value={value} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function PresentedValue({ value }: { value: unknown }) {
  if (value === SECRET) {
    return <span className="inline-flex items-center gap-1 text-muted-foreground"><EyeOff />secret</span>;
  }
  if (typeof value === "boolean") {
    return <Badge variant={value ? "info" : "muted"}>{String(value)}</Badge>;
  }
  return <>{displayValue(value)}</>;
}

function EnvironmentMetadata({ metadata }: { metadata: Record<string, unknown> }) {
  const environment = isRecord(metadata.environment) ? metadata.environment : {};
  const source = pickEntries(environment, [
    "vcs.kind", "vcs.repo", "vcs.owner", "git.head", "git.headName", "git.branch",
    "git.dirty", "git.author", "git.author.email", "git.committer", "git.committer.email",
  ]);
  const execution = pickEntries(environment, [
    "exec.kind", "exec.agent", "exec.runtime", "runtime.name", "pulumi.arch", "pulumi.version",
  ]);
  const otherMetadata = Object.fromEntries(Object.entries(metadata).filter(([key]) => key !== "environment" && key !== "message"));
  const remaining = Object.fromEntries(Object.entries(environment).filter(([key]) => !(key in source) && !(key in execution)));
  return (
    <Card className="xl:col-span-2">
      <CardHeader>
        <div className="flex items-center gap-2"><Monitor /><CardTitle>Run environment</CardTitle></div>
        <CardDescription>Source-control and execution context reported by the Pulumi CLI.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6 lg:grid-cols-2">
        <MetadataGroup icon={GitBranch} title="Source control" values={source} />
        <MetadataGroup icon={Monitor} title="Execution" values={execution} />
        {(Object.keys(otherMetadata).length > 0 || Object.keys(remaining).length > 0) && (
          <RawMetadata values={{ ...otherMetadata, ...remaining }} />
        )}
      </CardContent>
    </Card>
  );
}

function MetadataGroup({ icon: Icon, title, values }: { icon: typeof Monitor; title: string; values: Record<string, unknown> }) {
  return (
    <section className="flex min-w-0 flex-col gap-3">
      <h3 className="flex items-center gap-2 text-sm font-medium"><Icon />{title}</h3>
      {Object.keys(values).length === 0 ? <p className="text-sm text-muted-foreground">Not reported</p> : (
        <dl className="grid min-w-0 grid-cols-[minmax(7rem,auto)_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
          {Object.entries(values).map(([key, value]) => (
            <div className="contents" key={key}>
              <dt className="text-muted-foreground">{friendlyKey(key)}</dt>
              <dd className="truncate text-right font-mono text-xs" title={String(value)}>{displayValue(value)}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function RawMetadata({ values }: { values: Record<string, unknown> }) {
  return (
    <Collapsible className="lg:col-span-2">
      <CollapsibleTrigger asChild>
        <Button variant="ghost" size="sm"><ChevronDown data-icon="inline-start" />Show all reported metadata</Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <pre className="mt-3 max-h-72 overflow-auto rounded-md bg-muted p-4 font-mono text-xs whitespace-pre-wrap break-words">{JSON.stringify(values, null, 2)}</pre>
      </CollapsibleContent>
    </Collapsible>
  );
}

const SECRET = Symbol("secret");
export function maskConfig(config: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(config).map(([key, entry]) => [key, isSecretConfig(entry) ? SECRET : configValue(entry)]));
}
function isSecretConfig(value: unknown) { return typeof value === "object" && value !== null && "secret" in value && (value as { secret?: unknown }).secret === true; }
function configValue(value: unknown) {
  if (!isRecord(value)) return value;
  if ("string" in value) {
    const raw = String(value.string ?? "");
    if (value.object === true) {
      try { return JSON.parse(raw) as unknown; } catch { return raw; }
    }
    return raw;
  }
  return "value" in value ? value.value : value;
}
function displayValue(value: unknown) { return typeof value === "string" ? value : JSON.stringify(value, null, 2); }

export function stripAnsi(value: string) { return value.replace(/[\u001B\u009B][[\]()#;?]*(?:(?:(?:[a-zA-Z\d]*(?:;[-a-zA-Z\d\/#&.:=?%@~_]+)*)?\u0007)|(?:(?:\d{1,4}(?:[;:]\d{0,4})*)?[\dA-PR-TZcf-nq-uy=><~]))/g, ""); }

export function formatEventLine(event: RunEvent, preview: boolean): { marker: string; text: string } | null {
  const envelope = event.payload[`${event.kind}Event`];
  const body = isRecord(envelope) ? envelope : {};
  if (typeof body.message === "string") {
    const prefix = event.kind === "diagnostic" && typeof body.prefix === "string" ? body.prefix : "";
    return { marker: event.kind === "diagnostic" ? "! " : "  ", text: stripAnsi(`${prefix}${body.message}`) };
  }
  if (event.kind === "error" && typeof body.error === "string") return { marker: "!", text: body.error };
  if (["resourcePre", "resOutputs", "resOpFailed"].includes(event.kind)) {
    const metadata = isRecord(body.metadata) ? body.metadata : {};
    const op = String(metadata.op ?? "read");
    const urn = String(metadata.urn ?? "");
    const parts = urn.split("::");
    const name = parts[parts.length - 1] || "resource";
    const type = String(metadata.type ?? urnType(urn));
    if (op === "same" || isReadOperation(op)) return null;
    const phase = preview ? previewOperation(op) : event.kind === "resourcePre" ? operationVerb(op, false) : event.kind === "resOpFailed" ? `${operationVerb(op, false)} failed` : operationVerb(op, true);
    return { marker: event.kind === "resOpFailed" ? "!" : operationMarker(op), text: `${type} ${name} ${phase}` };
  }
  if (event.kind === "prelude") return { marker: "›", text: "Preparing update" };
  if (event.kind === "summary") {
    const changes = isRecord(body.resourceChanges) ? body.resourceChanges : {};
    const counts = conceptualSummaryCounts(changes);
    const pieces = Object.entries(counts).filter(([, count]) => count > 0).map(([op, count]) => `${count} ${summaryLabel(op, count, preview)}`);
    if (!preview && typeof body.durationSeconds === "number") pieces.push(formatSeconds(body.durationSeconds));
    return { marker: "✓ ", text: [preview ? "Preview complete" : "Update complete", ...pieces].join(" · ") };
  }
  if (["cancel", "policyLoad"].includes(event.kind)) return null;
  return { marker: "·", text: friendlyKey(event.kind) };
}

function diagnosticClass(event: RunEvent) { return cn(event.severity === "error" && "text-red-400", event.severity === "warning" && "text-amber-300"); }
function mergeEvents(current: RunEvent[], incoming: RunEvent[]) { return [...new Map([...current, ...incoming].map((event) => [event.sequence, event])).values()].sort((a, b) => a.sequence - b.sequence); }

export function resourceChanges(events: RunEvent[], collapseReplacements = false): ResourceChange[] {
  const changes = new Map<string, ResourceChange>();
  events.filter((event) => ["resourcePre", "resOutputs", "resOpFailed"].includes(event.kind)).forEach((event) => {
    const envelope = event.payload[`${event.kind}Event`] as Record<string, unknown> | undefined;
    const metadata = (envelope?.metadata ?? {}) as Record<string, unknown>;
    const urn = String(metadata.urn ?? "");
    const urnParts = urn.split("::");
    const rawOp = String(metadata.op ?? "read");
    const op = collapseReplacements && isReplacementOperation(rawOp) ? "replace" : rawOp;
    changes.set(`${urn}\0${op}`, { sequence: event.sequence, op, type: String(metadata.type ?? urnType(urn)), name: urnParts[urnParts.length - 1] ?? urn, detail: envelope ?? event.payload });
  });
  return [...changes.values()].sort((a, b) => a.sequence - b.sequence);
}

export function resourceRows(events: RunEvent[], preview: boolean): ResourceDisplayRow[] {
  const rows = new Map<string, ResourceDisplayRow>();
  const diagnostics = diagnosticCounts(events);
  const collapseReplacements = preview || events.some((event) => event.kind === "summary");
  for (const event of events) {
    if (!["resourcePre", "resOutputs", "resOpFailed"].includes(event.kind)) continue;
    const body = isRecord(event.payload[`${event.kind}Event`]) ? event.payload[`${event.kind}Event`] as Record<string, unknown> : {};
    const metadata = isRecord(body.metadata) ? body.metadata : {};
    const rawOp = String(metadata.op ?? "read");
    const type = String(metadata.type ?? "unknown");
    const urn = String(metadata.urn ?? "");
    const root = type === "pulumi:pulumi:Stack";
    if (!root && !diagnostics.has(urn) && (rawOp === "same" || isReadOperation(rawOp))) continue;
    const op = collapseReplacements && isReplacementOperation(rawOp) ? "replace" : rawOp;
    const current = rows.get(urn);
    const done = event.kind !== "resourcePre";
    const failed = event.kind === "resOpFailed";
    const state = isRecord(metadata.new) ? metadata.new : isRecord(metadata.old) ? metadata.old : {};
    const status = root && rawOp === "same"
      ? done ? "done" : "running"
      : preview ? previewOperation(op) : failed ? `${operationVerb(op, false)} failed` : operationVerb(op, done);
    const nameParts = urn.split("::");
    rows.set(urn, {
      sequence: current?.sequence ?? event.sequence,
      urn,
      parent: String(state.parent ?? ""),
      op,
      marker: root && rawOp === "same" ? "  " : operationMarker(op),
      type,
      name: nameParts[nameParts.length - 1] || "resource",
      status,
      info: diagnosticInfo(diagnostics.get(urn)),
      done,
      failed,
      detail: body,
    });
  }
  return [...rows.values()].sort((a, b) => a.sequence - b.sequence);
}

export function propertyChanges(metadata: Record<string, unknown>): PropertyChange[] {
  const oldState = isRecord(metadata.old) ? metadata.old : {};
  const newState = isRecord(metadata.new) ? metadata.new : {};
  const oldInputs = isRecord(oldState.inputs) ? oldState.inputs : {};
  const oldOutputs = isRecord(oldState.outputs) ? oldState.outputs : {};
  const newInputs = isRecord(newState.inputs) ? newState.inputs : {};
  const detailed = isRecord(metadata.detailedDiff) ? metadata.detailedDiff : {};
  const hidden = Array.isArray(newState.hideDiffs) ? newState.hideDiffs.map(String) : [];
  const paths = Object.keys(detailed).length > 0
    ? Object.keys(detailed)
    : Array.isArray(metadata.diffs) ? metadata.diffs.map(String) : changedTopLevelKeys(oldInputs, newInputs);
  return paths.filter((path) => !hidden.some((hiddenPath) => path === hiddenPath || path.startsWith(`${hiddenPath}.`) || path.startsWith(`${hiddenPath}[`))).sort().map((path) => {
    const diff = isRecord(detailed[path]) ? detailed[path] as Record<string, unknown> : {};
    const oldValues = diff.inputDiff === true ? oldInputs : oldOutputs;
    return {
      path,
      kind: String(diff.diffKind ?? inferDiffKind(readPropertyPath(oldValues, path), readPropertyPath(newInputs, path))),
      oldValue: readPropertyPath(oldValues, path),
      newValue: readPropertyPath(newInputs, path),
    };
  });
}

type JsonDiffLine = { kind: "context" | "delete" | "add"; text: string };

export function jsonDiffLines(properties: PropertyChange[]): JsonDiffLine[] {
  const lines: JsonDiffLine[] = [];
  for (const property of properties) {
    lines.push({ kind: "context", text: property.path });
    if (property.oldValue !== undefined) {
      lines.push(...jsonValueLines("delete", maskPulumiSecrets(property.oldValue)));
    }
    if (property.newValue !== undefined) {
      lines.push(...jsonValueLines("add", maskPulumiSecrets(property.newValue)));
    }
  }
  return lines;
}

function jsonValueLines(
  kind: "delete" | "add",
  value: unknown,
): JsonDiffLine[] {
  const serialized = JSON.stringify(value, null, 2) ?? "null";
  return serialized.split("\n").map((text) => ({ kind, text }));
}

export function stackOutputsFromEvents(events: RunEvent[]): Record<string, unknown> {
  for (let index = events.length - 1; index >= 0; index--) {
    const event = events[index];
    if (event.kind !== "resOutputs") continue;
    const body = isRecord(event.payload.resOutputsEvent) ? event.payload.resOutputsEvent : {};
    const metadata = isRecord(body.metadata) ? body.metadata : {};
    if (metadata.type !== "pulumi:pulumi:Stack") continue;
    const state = isRecord(metadata.new) ? metadata.new : {};
    return isRecord(state.outputs) ? maskPulumiSecrets(state.outputs) as Record<string, unknown> : {};
  }
  return {};
}
function urnType(urn: string) { const parts = urn.split("::"); const types = parts[parts.length - 2]?.split("$") ?? []; return types[types.length - 1] ?? "unknown"; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
function pickEntries(values: Record<string, unknown>, keys: string[]) { return Object.fromEntries(keys.filter((key) => key in values).map((key) => [key, values[key]])); }
function friendlyKey(value: string) { return value.replace(/^pulumi\./, "").replace(/[._-]/g, " ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (letter) => letter.toUpperCase()); }
function operationMarker(op: string) {
  const markers: Record<string, string> = {
    same: "  ", create: "+ ", delete: "- ", update: "~ ", replace: "+-",
    "create-replacement": "++", "delete-replaced": "--", read: "> ",
    "read-replacement": ">>", refresh: "~ ", "read-discard": "< ",
    "discard-replaced": "<<", import: "= ", "import-replacement": "=>",
  };
  return markers[op] ?? "› ";
}
function markerClass(marker: string) { return cn(marker.startsWith("+") && "text-emerald-400", marker.startsWith("~") && "text-amber-300", marker.startsWith("-") && "text-red-400", marker.startsWith("!") && "text-red-400", marker.startsWith("✓") && "text-emerald-400", marker.trim() === "" && "text-zinc-500"); }
function operationVerb(op: string, complete: boolean) {
  const verbs: Record<string, [string, string]> = {
    create: ["creating", "created"],
    "create-replacement": ["creating replacement", "replacement created"],
    update: ["updating", "updated"],
    delete: ["deleting", "deleted"],
    "delete-replaced": ["deleting replaced resource", "replaced resource deleted"],
    replace: ["replacing", "replaced"],
    read: ["reading", "read"],
    refresh: ["refreshing", "refreshed"],
    import: ["importing", "imported"],
    same: ["unchanged", "unchanged"],
  };
  return (verbs[op] ?? [op, op])[complete ? 1 : 0];
}
function previewOperation(op: string) {
  const operations: Record<string, string> = {
    create: "create", update: "update", delete: "delete", replace: "replace",
    "create-replacement": "replace", "delete-replaced": "replace", read: "read",
    refresh: "refresh", import: "import",
  };
  return operations[op] ?? op.split("-").join(" ");
}
function summaryLabel(op: string, count: number, preview: boolean) {
  const labels: Record<string, string> = { create: "created", update: "updated", delete: "deleted", same: "unchanged", replace: "replaced" };
  if (preview && op !== "same") return `to ${previewOperation(op)}`;
  const label = labels[op] ?? op.split("-").join(" ");
  return count === 1 && label.endsWith("s") ? label.slice(0, -1) : label;
}
function conceptualSummaryCounts(changes: Record<string, unknown>) {
  const replacementSubsteps = Math.max(
    Number(changes["create-replacement"] ?? 0),
    Number(changes["delete-replaced"] ?? 0),
    Number(changes["discard-replaced"] ?? 0),
  );
  return {
    create: Number(changes.create ?? 0),
    update: Number(changes.update ?? 0),
    delete: Number(changes.delete ?? 0),
    replace: Number(changes.replace ?? replacementSubsteps),
    same: Number(changes.same ?? 0),
  };
}
function isReadOperation(op: string) { return op === "read" || op.includes("read-"); }
function isReplacementOperation(op: string) { return op === "replace" || op.includes("replacement") || op.includes("replaced"); }
function resourceDepth(row: ResourceDisplayRow, rows: ResourceDisplayRow[]) {
  const byUrn = new Map(rows.map((candidate) => [candidate.urn, candidate]));
  let parent = row.parent;
  let depth = 0;
  while (parent && byUrn.has(parent) && depth < 8) { depth++; parent = byUrn.get(parent)?.parent ?? ""; }
  return depth;
}
function diagnosticCounts(events: RunEvent[]) {
  const counts = new Map<string, { errors: number; warnings: number; messages: number }>();
  for (const event of events) {
    if (event.kind !== "diagnostic") continue;
    const body = isRecord(event.payload.diagnosticEvent) ? event.payload.diagnosticEvent : {};
    const urn = String(body.urn ?? "");
    const count = counts.get(urn) ?? { errors: 0, warnings: 0, messages: 0 };
    if (event.severity === "error") count.errors++; else if (event.severity === "warning") count.warnings++; else count.messages++;
    counts.set(urn, count);
  }
  return counts;
}
function diagnosticInfo(count?: { errors: number; warnings: number; messages: number }) {
  if (!count) return "";
  return [[count.errors, "error"], [count.warnings, "warning"], [count.messages, "message"]]
    .filter(([value]) => Number(value) > 0)
    .map(([value, label]) => `${value} ${label}${value === 1 ? "" : "s"}`)
    .join(", ");
}
function changedTopLevelKeys(oldValues: Record<string, unknown>, newValues: Record<string, unknown>) {
  return [...new Set([...Object.keys(oldValues), ...Object.keys(newValues)])]
    .filter((key) => JSON.stringify(oldValues[key]) !== JSON.stringify(newValues[key]));
}
function readPropertyPath(value: unknown, path: string): unknown {
  const segments = path.replace(/\["([^"\]]+)"\]/g, ".$1").replace(/\[(\d+)\]/g, ".$1").split(".").filter(Boolean);
  return segments.reduce<unknown>((current, segment) => {
    if (Array.isArray(current)) return current[Number(segment)];
    return isRecord(current) ? current[segment] : undefined;
  }, value);
}
function inferDiffKind(oldValue: unknown, newValue: unknown) { return oldValue === undefined ? "add" : newValue === undefined ? "delete" : "update"; }
function maskPulumiSecrets(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(maskPulumiSecrets);
  if (!isRecord(value)) return value;
  if ("secure" in value || "ciphertext" in value || value["4dabf18193072939515e22adb298388d"] === "1") return "[secret]";
  return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, maskPulumiSecrets(entry)]));
}
function formatSeconds(seconds: number) { const minutes = Math.floor(seconds / 60); const rest = seconds % 60; return minutes > 0 ? `${minutes}m ${rest}s` : `${seconds}s`; }
function OperationBadge({ op }: { op: string }) { const variant: BadgeProps["variant"] = op.includes("create") ? "success" : op.includes("delete") ? "destructive" : op.includes("update") ? "warning" : op.includes("replace") ? "info" : "muted"; return <Badge variant={variant}>{op}</Badge>; }
function runTitle(run: RunDetail) { return run.version === null ? "Preview" : `${run.kind[0].toUpperCase()}${run.kind.slice(1)} v${run.version}`; }
export function formatRunDuration(startedAt: string, endedAt: string | null, now = Date.now()) { const seconds = Math.max(0, Math.floor(((endedAt ? new Date(endedAt).getTime() : now) - new Date(startedAt).getTime()) / 1000)); const minutes = Math.floor(seconds / 60); const rest = seconds % 60; return minutes ? `${minutes}m ${rest}s` : `${rest}s`; }
function changeSummary(run: RunDetail) { const counts = run.summary?.resourceChanges ?? {}; const count = (key: string) => counts[key] ?? 0; return { created: count("create") + count("create-replacement"), updated: count("update"), deleted: count("delete") + count("delete-replaced"), unchanged: count("same") }; }
function RunDetailSkeleton() { return <div className="flex flex-col gap-4"><Skeleton className="h-8 w-2/3" /><Skeleton className="h-5 w-1/2" /><Skeleton className="h-[32rem] w-full" /></div>; }
