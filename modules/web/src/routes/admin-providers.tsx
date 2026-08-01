import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { Check, Copy, Pencil, Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";

import { DataTable, sortableHeader } from "@/components/console/DataTable";
import { ErrorAlert } from "@/components/console/ErrorAlert";
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import {
  api,
  type OidcProvider,
  type OidcProviderWrite,
  UnauthorizedError,
} from "@/lib/api";

const PROVIDERS_KEY = ["admin", "providers"] as const;
const NAME_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

type ProviderForm = {
  name: string;
  issuerUrl: string;
  clientId: string;
  clientSecret: string;
  scopes: string;
};

export function AdminProvidersPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<OidcProvider | null | undefined>();
  const [confirmDisable, setConfirmDisable] = useState<OidcProvider | null>(null);
  const query = useQuery({
    queryKey: PROVIDERS_KEY,
    queryFn: api.admin.providers.list,
    retry: (count, error) => !(error instanceof UnauthorizedError) && count < 2,
  });
  const unauthorized = useUnauthorizedRedirect(query.error);

  const toggle = useMutation({
    mutationFn: ({ provider, enabled }: { provider: OidcProvider; enabled: boolean }) =>
      api.admin.providers.update(provider.id, { enabled }),
    onSuccess: (provider) => {
      queryClient.setQueryData<OidcProvider[]>(PROVIDERS_KEY, (providers = []) =>
        providers.map((item) => item.id === provider.id ? provider : item),
      );
      toast.success(provider.enabled ? `${provider.name} enabled` : `${provider.name} disabled`);
    },
    onError: (error) => toast.error(errorMessage(error)),
  });

  const providers = query.data ?? [];
  const enabledCount = providers.filter((provider) => provider.enabled).length;

  const setEnabled = (provider: OidcProvider, enabled: boolean) => {
    if (!enabled && provider.enabled && enabledCount === 1) {
      setConfirmDisable(provider);
      return;
    }
    toggle.mutate({ provider, enabled });
  };

  const columns = useMemo<ColumnDef<OidcProvider>[]>(() => [
    {
      accessorKey: "name",
      header: sortableHeader("Name"),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <span className="font-medium">{row.original.name}</span>
          {row.original.envManaged && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Badge variant="secondary">env-managed</Badge>
              </TooltipTrigger>
              <TooltipContent>
                Environment configuration wins on every boot, so this provider is read-only.
              </TooltipContent>
            </Tooltip>
          )}
        </div>
      ),
    },
    {
      accessorKey: "issuerUrl",
      header: "Issuer URL",
      cell: ({ row }) => (
        <span className="block max-w-72 truncate" title={row.original.issuerUrl}>
          {row.original.issuerUrl}
        </span>
      ),
    },
    {
      accessorKey: "clientId",
      header: "Client ID",
      cell: ({ row }) => (
        <code className="block max-w-56 truncate text-xs" title={row.original.clientId}>
          {row.original.clientId}
        </code>
      ),
    },
    {
      id: "callbackUrl",
      header: "Callback URL",
      cell: ({ row }) => <CallbackUrl provider={row.original} />,
    },
    {
      accessorKey: "enabled",
      header: "Enabled",
      cell: ({ row }) => (
        <Switch
          checked={row.original.enabled}
          disabled={row.original.envManaged || toggle.isPending}
          aria-label={`${row.original.enabled ? "Disable" : "Enable"} ${row.original.name}`}
          onCheckedChange={(enabled) => setEnabled(row.original, enabled)}
        />
      ),
    },
    {
      id: "edit",
      cell: ({ row }) => row.original.envManaged ? null : (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={`Edit ${row.original.name}`}
          onClick={() => setEditing(row.original)}
        >
          <Pencil />
        </Button>
      ),
    },
  ], [enabledCount, toggle.isPending]);

  if (unauthorized) return null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">OIDC providers</h1>
        <p className="text-sm text-muted-foreground">
          Manage the identity providers users can use to sign in to the console.
        </p>
      </div>

      {query.error ? (
        <ErrorAlert
          title="Could not load OIDC providers"
          error={query.error}
          onRetry={() => void query.refetch()}
        />
      ) : query.isLoading ? (
        <ProvidersSkeleton />
      ) : (
        <DataTable
          columns={columns}
          data={providers}
          filterColumn="name"
          filterPlaceholder="Filter providers..."
          emptyTitle="No OIDC providers"
          emptyDescription="Add a provider to enable OIDC sign-in."
          toolbar={
            <div className="flex justify-end">
              <Button type="button" onClick={() => setEditing(null)}>
                <Plus data-icon="inline-start" />
                Add provider
              </Button>
            </div>
          }
        />
      )}

      <ProviderDialog
        provider={editing}
        onOpenChange={(open) => { if (!open) setEditing(undefined); }}
      />

      <AlertDialog
        open={Boolean(confirmDisable)}
        onOpenChange={(open) => { if (!open) setConfirmDisable(null); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Disable the last OIDC provider?</AlertDialogTitle>
            <AlertDialogDescription>
              Users will no longer be able to sign in with OIDC. Only break-glass access will
              remain until a provider is enabled again.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (confirmDisable) toggle.mutate({ provider: confirmDisable, enabled: false });
                setConfirmDisable(null);
              }}
            >
              Disable provider
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function ProviderDialog({
  provider,
  onOpenChange,
}: {
  provider: OidcProvider | null | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const editing = provider != null;
  const values = useMemo(() => formValues(provider), [provider]);
  const form = useForm<ProviderForm>({ values });
  const mutation = useMutation({
    mutationFn: (values: ProviderForm) => {
      const body = providerWrite(values, editing);
      return editing
        ? api.admin.providers.update(provider.id, body)
        : api.admin.providers.create(body);
    },
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: PROVIDERS_KEY });
      toast.success(`${saved.name} ${editing ? "updated" : "added"}`);
      onOpenChange(false);
    },
    onError: (error) => toast.error(errorMessage(error)),
  });

  return (
    <Dialog open={provider !== undefined} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? `Edit ${provider.name}` : "Add OIDC provider"}</DialogTitle>
          <DialogDescription>
            {editing
              ? "Update the connection settings. Leave the secret blank to keep it unchanged."
              : "Enter the OIDC application details from your identity provider."}
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form className="flex flex-col gap-4" onSubmit={(event) => void form.handleSubmit((values) => mutation.mutate(values))(event)}>
            <FormField
              control={form.control}
              name="name"
              rules={{
                required: "Name is required.",
                pattern: { value: NAME_PATTERN, message: "Use 1–63 lowercase letters, numbers, or hyphens." },
              }}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl><Input autoComplete="off" placeholder="company-sso" {...field} /></FormControl>
                  <FormDescription>Used in the callback URL and cannot contain spaces.</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="issuerUrl"
              rules={{
                required: "Issuer URL is required.",
                validate: (value) => isHttpUrl(value) || "Enter an absolute HTTP(S) URL.",
              }}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Issuer URL</FormLabel>
                  <FormControl><Input type="url" placeholder="https://id.example.com" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="clientId"
              rules={{ required: "Client ID is required." }}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Client ID</FormLabel>
                  <FormControl><Input autoComplete="off" {...field} /></FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="clientSecret"
              rules={{ required: editing ? false : "Client secret is required." }}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Client secret</FormLabel>
                  <FormControl>
                    <Input
                      type="password"
                      autoComplete="new-password"
                      placeholder={editing ? "••• unchanged" : undefined}
                      {...field}
                    />
                  </FormControl>
                  {editing && <FormDescription>The saved secret is never returned by the API.</FormDescription>}
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="scopes"
              rules={{
                required: "At least one scope is required.",
                validate: (value) => parseScopes(value).includes("openid") || "Scopes must include openid.",
              }}
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Scopes</FormLabel>
                  <FormControl><Input placeholder="openid, profile, email" {...field} /></FormControl>
                  <FormDescription>Comma-separated. The openid scope is required.</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={mutation.isPending}>
                {mutation.isPending ? "Saving…" : editing ? "Save changes" : "Add provider"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

function CallbackUrl({ provider }: { provider: OidcProvider }) {
  const [copied, setCopied] = useState(false);
  const value = `${window.location.origin}/api/auth/callback/${provider.name}`;
  return (
    <div className="flex max-w-80 items-center gap-1">
      <code className="truncate text-xs" title={value}>{value}</code>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        aria-label={`Copy callback URL for ${provider.name}`}
        onClick={() => {
          void navigator.clipboard.writeText(value).then(() => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1_500);
          });
        }}
      >
        {copied ? <Check /> : <Copy />}
      </Button>
    </div>
  );
}

function ProvidersSkeleton() {
  return (
    <div className="flex flex-col gap-3">
      <Skeleton className="ml-auto h-9 w-32" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-56 w-full" />
    </div>
  );
}

function formValues(provider: OidcProvider | null | undefined): ProviderForm {
  return {
    name: provider?.name ?? "",
    issuerUrl: provider?.issuerUrl ?? "",
    clientId: provider?.clientId ?? "",
    clientSecret: "",
    scopes: provider?.scopes.join(", ") ?? "openid, profile, email",
  };
}

export function parseScopes(value: string) {
  return [...new Set(value.split(",").map((scope) => scope.trim()).filter(Boolean))];
}

export function isHttpUrl(value: string) {
  try {
    const url = new URL(value);
    return (url.protocol === "https:" || url.protocol === "http:") && Boolean(url.hostname);
  } catch {
    return false;
  }
}

export function providerWrite(values: ProviderForm, editing: boolean): OidcProviderWrite {
  return {
    name: values.name.trim(),
    issuerUrl: values.issuerUrl.trim(),
    clientId: values.clientId.trim(),
    clientSecret: values.clientSecret || undefined,
    scopes: parseScopes(values.scopes),
    ...(!editing && { enabled: false }),
  };
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message !== "" ? error.message : "The provider could not be saved.";
}
