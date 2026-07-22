import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { formatDistanceToNow } from "date-fns";
import { KeyRound, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";

import { DataTable, sortableHeader } from "@/components/console/DataTable";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { OneTimeTokenDialog } from "@/components/console/OneTimeTokenDialog";
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
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, type ApiToken, UnauthorizedError } from "@/lib/api";

const TOKENS_KEY = ["tokens"] as const;

export function AccountTokensPage() {
  const queryClient = useQueryClient();
  const [revokeTarget, setRevokeTarget] = useState<ApiToken | null>(null);
  const [minted, setMinted] = useState(readMintedToken);
  // The raw token arrives in the URL fragment; strip it immediately once captured into state so it
  // never lingers in the address bar, history, or a bookmark. The dialog reads it from state.
  useEffect(() => {
    if (minted.rawToken) clearMintedToken();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const query = useQuery({
    queryKey: TOKENS_KEY,
    queryFn: api.tokens.list,
    retry: (count, error) => !(error instanceof UnauthorizedError) && count < 2,
  });
  const unauthorized = useUnauthorizedRedirect(query.error);
  const revoke = useMutation({
    mutationFn: api.tokens.revoke,
    onSuccess: (_, id) => {
      queryClient.setQueryData<ApiToken[]>(TOKENS_KEY, (tokens = []) =>
        tokens.filter((token) => token.id !== id),
      );
      setRevokeTarget(null);
      toast.success("Token revoked");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not revoke token"),
  });

  const columns = useMemo<ColumnDef<ApiToken>[]>(() => [
    {
      accessorKey: "description",
      header: sortableHeader("Description"),
      cell: ({ row }) => <span className="font-medium">{row.original.description}</span>,
    },
    {
      accessorKey: "kind",
      header: "Kind",
      cell: ({ row }) => <Badge variant="secondary">{row.original.kind}</Badge>,
    },
    {
      accessorKey: "createdAt",
      header: sortableHeader("Created"),
      cell: ({ row }) => <Timestamp value={row.original.createdAt} />,
    },
    {
      accessorKey: "expiresAt",
      header: sortableHeader("Expires"),
      cell: ({ row }) => <Expiry value={row.original.expiresAt} />,
    },
    {
      accessorKey: "lastUsedAt",
      header: sortableHeader("Last used"),
      cell: ({ row }) => row.original.lastUsedAt ? <Timestamp value={row.original.lastUsedAt} /> : "Never",
    },
    {
      id: "actions",
      header: "",
      cell: ({ row }) => (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={`Revoke ${row.original.description}`}
          onClick={() => setRevokeTarget(row.original)}
        >
          <Trash2 data-icon="inline-start" />
        </Button>
      ),
    },
  ], []);

  if (unauthorized) return null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Personal access tokens</h1>
        <p className="text-muted-foreground">Create and revoke credentials for the Pulumi CLI.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Get a CLI token</CardTitle>
          <CardDescription>
            Create a 24-hour token bound to your current identity. The token is shown only once.
          </CardDescription>
        </CardHeader>
        <CardContent>
          Use it with <code className="font-mono text-sm">pulumi login</code> from your terminal.
        </CardContent>
        <CardFooter>
          {/* Form POST, not a link: keep minting off GET. Browser follows the server redirect. */}
          <form method="post" action="/api/auth/cli-mint">
            <Button type="submit"><KeyRound data-icon="inline-start" />Create CLI token</Button>
          </form>
        </CardFooter>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Use a CLI token</CardTitle>
          <CardDescription>
            Export the token you copied, then connect Pulumi to this state-db instance.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Command value="export PULUMI_ACCESS_TOKEN=&lt;your-token&gt;" />
          <Command value={`pulumi login ${window.location.origin}/pulumi`} />
          <Command value={`export PULUMI_CONSOLE_DOMAIN=${window.location.host}`} />
        </CardContent>
      </Card>

      <section className="flex flex-col gap-3">
        <div>
          <h2 className="text-lg font-semibold">Tokens</h2>
          <p className="text-sm text-muted-foreground">Revocation takes effect immediately.</p>
        </div>
        {query.isLoading && <TokenTableSkeleton />}
        {query.isError && !unauthorized && (
          <ErrorAlert error={query.error} onRetry={() => void query.refetch()} />
        )}
        {query.isSuccess && (
          <DataTable
            columns={columns}
            data={query.data}
            emptyTitle="No tokens yet"
            emptyDescription="Create a 24-hour CLI token above when you need one."
          />
        )}
      </section>

      <AlertDialog open={revokeTarget !== null} onOpenChange={(open) => !open && setRevokeTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Revoke this token?</AlertDialogTitle>
            <AlertDialogDescription>
              CLI calls with this token fail immediately. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => revokeTarget && revoke.mutate(revokeTarget.id)}
              disabled={revoke.isPending}
            >
              Revoke token
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <OneTimeTokenDialog
        rawToken={minted.rawToken}
        onClose={() => {
          clearMintedToken();
          setMinted({ id: null, rawToken: null });
        }}
      />
    </div>
  );
}

function Expiry({ value }: { value: string }) {
  const date = new Date(value);
  const remaining = date.getTime() - Date.now();
  const state = expirationState(value);
  const label = remaining <= 0 ? "Expired" : formatDistanceToNow(date);
  if (state === "expired") return <Badge variant="destructive">{label}</Badge>;
  if (state === "expiring") return <Badge variant="warning">{label}</Badge>;
  return <Timestamp value={value} />;
}

function Command({ value }: { value: string }) {
  return (
    <code className="overflow-x-auto rounded-md border bg-muted px-3 py-2 font-mono text-sm">
      {value}
    </code>
  );
}

function TokenTableSkeleton() {
  return (
    <div className="flex flex-col gap-2 rounded-md border p-4">
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
    </div>
  );
}

export function readMintedToken() {
  const query = new URLSearchParams(window.location.search);
  const fragment = new URLSearchParams(window.location.hash.slice(1));
  return {
    id: query.get("minted"),
    rawToken: query.has("minted") ? fragment.get("token") : null,
  };
}

export function clearMintedToken() {
  const url = new URL(window.location.href);
  url.searchParams.delete("minted");
  url.hash = "";
  window.history.replaceState(null, "", `${url.pathname}${url.search}`);
}

export function expirationState(value: string, now = Date.now()) {
  const remaining = new Date(value).getTime() - now;
  if (remaining <= 0) return "expired";
  if (remaining < 7_200_000) return "expiring";
  return "active";
}
