import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Unlock } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Timestamp } from "@/components/console/Timestamp";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
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
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import {
  api,
  type AdminCompactionJob,
  type AdminLock,
  UnauthorizedError,
} from "@/lib/api";

const LOCKS_KEY = ["admin", "operations", "locks"] as const;
const JOBS_KEY = ["admin", "operations", "compaction-jobs"] as const;

export function AdminOperationsPage() {
  const locks = useQuery({
    queryKey: LOCKS_KEY,
    queryFn: api.admin.operations.locks,
    refetchInterval: 5_000,
    retry: retryQuery,
  });
  const jobs = useQuery({
    queryKey: JOBS_KEY,
    queryFn: api.admin.operations.compactionJobs,
    refetchInterval: 5_000,
    retry: retryQuery,
  });
  const unauthorized = useUnauthorizedRedirect(locks.error ?? jobs.error);

  if (unauthorized) return null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Operations</h1>
        <p className="text-sm text-muted-foreground">
          Diagnose active stack locks and compaction queue health.
        </p>
      </div>
      <LocksCard query={locks} />
      <CompactionCard query={jobs} />
    </div>
  );
}

function LocksCard({ query }: { query: UseQueryResult<AdminLock[], Error> }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<AdminLock | null>(null);
  const unlock = useMutation({
    mutationFn: (lock: AdminLock) =>
      api.workspaces.forceUnlock(lock.org, lock.project, lock.stack, lock.lockId),
    onSuccess: async (_, lock) => {
      setSelected(null);
      toast.success(`${lock.org}/${lock.project}/${lock.stack} unlocked`);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: LOCKS_KEY }),
        queryClient.invalidateQueries({
          queryKey: ["workspace", lock.org, lock.project, lock.stack],
        }),
      ]);
    },
    onError: (error) =>
      toast.error("Could not force unlock stack", { description: error.message }),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Active locks</CardTitle>
        <CardDescription>Live Pulumi update leases. Expired leases are excluded.</CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <TableSkeleton />
        ) : query.error ? (
          <ErrorAlert
            title="Could not load active locks"
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        ) : (
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Stack</TableHead>
                  <TableHead>Locked by</TableHead>
                  <TableHead>Acquired</TableHead>
                  <TableHead>Expires</TableHead>
                  <TableHead>Update</TableHead>
                  <TableHead className="text-right">Action</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data?.length ? (
                  query.data.map((lock) => (
                    <TableRow key={lock.lockId}>
                      <TableCell><StackLink item={lock} /></TableCell>
                      <TableCell><LockHolder lock={lock} /></TableCell>
                      <TableCell><Timestamp value={lock.acquiredAt} /></TableCell>
                      <TableCell><Countdown expiresAt={lock.expiresAt} /></TableCell>
                      <TableCell><UpdateLink lock={lock} /></TableCell>
                      <TableCell className="text-right">
                        <Button
                          type="button"
                          variant="destructive"
                          size="sm"
                          onClick={() => setSelected(lock)}
                        >
                          <Unlock />Force unlock
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))
                ) : (
                  <EmptyRow columns={6} title="No stacks are locked." />
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>

      <AlertDialog
        open={selected !== null}
        onOpenChange={(open) => !open && setSelected(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Force-unlock {selected
                ? `${selected.org}/${selected.project}/${selected.stack}`
                : "stack"}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              This cancels the associated update and releases its lease. A Pulumi
              process that is still running will fail on its next request.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {selected && (
            <dl className="grid gap-2 rounded-md border p-3 text-sm sm:grid-cols-[8rem_1fr]">
              <dt className="text-muted-foreground">Holder</dt>
              <dd>{holderLabel(selected)}</dd>
              <dt className="text-muted-foreground">Acquired</dt>
              <dd><Timestamp value={selected.acquiredAt} /></dd>
              <dt className="text-muted-foreground">Last heartbeat</dt>
              <dd><Timestamp value={selected.heartbeatAt} /></dd>
            </dl>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className={buttonVariants({ variant: "destructive" })}
              disabled={unlock.isPending}
              onClick={() => selected && unlock.mutate(selected)}
            >
              Force unlock
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

function CompactionCard({ query }: { query: UseQueryResult<AdminCompactionJob[], Error> }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Compaction</CardTitle>
        <CardDescription>
          Active queue entries followed by the 50 most recent completed or failed jobs.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <TableSkeleton />
        ) : query.error ? (
          <ErrorAlert
            title="Could not load compaction jobs"
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        ) : (
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Stack</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Attempts</TableHead>
                  <TableHead>Queued</TableHead>
                  <TableHead>Started / completed</TableHead>
                  <TableHead>Error</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data?.length ? (
                  query.data.map((job) => (
                    <TableRow key={job.id}>
                      <TableCell><StackLink item={job} /></TableCell>
                      <TableCell><CompactionStatus status={job.status} /></TableCell>
                      <TableCell>{job.attempts}</TableCell>
                      <TableCell><Timestamp value={job.createdAt} /></TableCell>
                      <TableCell className="min-w-36">
                        {job.startedAt ? (
                          <div>Started <Timestamp value={job.startedAt} /></div>
                        ) : (
                          <span className="text-muted-foreground">Not started</span>
                        )}
                        {job.completedAt && (
                          <div>Completed <Timestamp value={job.completedAt} /></div>
                        )}
                      </TableCell>
                      <TableCell><CompactionError job={job} /></TableCell>
                    </TableRow>
                  ))
                ) : (
                  <EmptyRow columns={6} title="No compaction jobs." />
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function StackLink({ item }: { item: Pick<AdminLock, "org" | "project" | "stack"> }) {
  return (
    <Link
      className="font-medium hover:underline"
      to="/console/$org/$project/$stack"
      params={item}
    >
      {item.org}/{item.project}/{item.stack}
    </Link>
  );
}

function UpdateLink({ lock }: { lock: AdminLock }) {
  return lock.update.preview ? (
    <Link
      className="hover:underline"
      to="/console/$org/$project/$stack/previews/$updateId"
      params={{ ...lock, updateId: lock.update.id }}
    >
      Preview
    </Link>
  ) : (
    <Link
      className="hover:underline"
      to="/console/$org/$project/$stack/updates/$version"
      params={{ ...lock, version: String(lock.update.version) }}
    >
      Update #{lock.update.version}
    </Link>
  );
}

function LockHolder({ lock }: { lock: AdminLock }) {
  return (
    <div className="flex items-center gap-2">
      <span>{holderLabel(lock)}</span>
      {lock.holder.kind === "service" && <Badge variant="outline">service</Badge>}
    </div>
  );
}

export function holderLabel(lock: Pick<AdminLock, "holder">): string {
  return lock.holder.displayName ?? lock.holder.email ?? lock.holder.fallback;
}

function Countdown({ expiresAt }: { expiresAt: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, []);
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <time dateTime={expiresAt}>{formatCountdown(expiresAt, now)}</time>
      </TooltipTrigger>
      <TooltipContent>{new Date(expiresAt).toISOString()}</TooltipContent>
    </Tooltip>
  );
}

export function formatCountdown(expiresAt: string, now: number): string {
  const seconds = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 1_000));
  if (seconds === 0) return "expired";
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return minutes > 0 ? `${minutes}m ${remainder}s` : `${remainder}s`;
}

function CompactionStatus({ status }: { status: AdminCompactionJob["status"] }) {
  const variants = {
    pending: "muted",
    running: "info",
    completed: "success",
    failed: "destructive",
  } as const;
  return <Badge variant={variants[status]}>{status}</Badge>;
}

function CompactionError({ job }: { job: AdminCompactionJob }) {
  if (!job.errorMessage) return <span className="text-muted-foreground">—</span>;
  const prefix = job.status === "pending" ? "Last attempt: " : "";
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="block max-w-64 truncate">{prefix}{job.errorMessage}</span>
      </TooltipTrigger>
      <TooltipContent className="max-w-96 whitespace-normal">
        {prefix}{job.errorMessage}
      </TooltipContent>
    </Tooltip>
  );
}

function EmptyRow({ columns, title }: { columns: number; title: string }) {
  return (
    <TableRow>
      <TableCell colSpan={columns} className="h-32 text-center text-muted-foreground">
        {title}
      </TableCell>
    </TableRow>
  );
}

function TableSkeleton() {
  return (
    <div className="flex flex-col gap-3">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-16 w-full" />
    </div>
  );
}

function retryQuery(count: number, error: Error) {
  return !(error instanceof UnauthorizedError) && count < 2;
}
