import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { formatDistanceToNow } from "date-fns";
import { KeyRound, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { toast } from "sonner";

import { DataTable, sortableHeader } from "@/components/console/DataTable";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { OneTimeTokenDialog } from "@/components/console/OneTimeTokenDialog";
import { Timestamp } from "@/components/console/Timestamp";
import { useShell } from "@/components/shell/AppShell";
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
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldContent, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api, type CurrentUser, type MintedServiceAccount, type ServiceAccount } from "@/lib/api";

export type ServiceAccountFilter = "active" | "inactive" | "all";
export type ServiceAccountStatus = "active" | "expired" | "revoked";

const KEY = ["service-accounts"] as const;
const EXPIRY_WARNING_MS = 7 * 24 * 60 * 60 * 1000;

export function canManageServiceTokens(
  user: Pick<CurrentUser, "siteAdmin" | "organizations">,
): boolean {
  return user.siteAdmin || user.organizations.some((org) => org.role === "owner");
}

export function serviceAccountStatus(
  account: Pick<ServiceAccount, "tokenRevokedAt" | "accountDeactivatedAt" | "tokenExpiresAt">,
  now = Date.now(),
): ServiceAccountStatus {
  if (account.tokenRevokedAt || account.accountDeactivatedAt) return "revoked";
  if (Date.parse(account.tokenExpiresAt) <= now) return "expired";
  return "active";
}

export function expiryState(
  value: string,
  now = Date.now(),
): "expired" | "expiring" | "healthy" {
  const remaining = Date.parse(value) - now;
  if (remaining <= 0) return "expired";
  return remaining <= EXPIRY_WARNING_MS ? "expiring" : "healthy";
}

function actor(name: string | null, email: string | null, fallback: string) {
  return name ?? email ?? fallback;
}

export function serviceTokenShortId(tokenId: string): string {
  return tokenId.slice(0, 8);
}

export function AdminServiceTokensPage() {
  const { user, selectedOrg } = useShell();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<ServiceAccountFilter>("active");
  const [mintOpen, setMintOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ServiceAccount | null>(null);
  const [minted, setMinted] = useState<MintedServiceAccount | null>(null);
  const query = useQuery({
    queryKey: [...KEY, filter],
    queryFn: () => api.serviceAccounts.list(filter),
  });
  const activeQuery = useQuery({
    queryKey: [...KEY, "active"],
    queryFn: () => api.serviceAccounts.list("active"),
  });
  const allOrgs = useQuery({
    queryKey: ["admin", "orgs"],
    queryFn: api.admin.orgs.list,
    enabled: user.siteAdmin,
  });
  const manageableOrgs = user.siteAdmin
    ? (allOrgs.data ?? []).map((org) => ({ name: org.name, displayName: org.displayName }))
    : user.organizations.filter((org) => org.role === "owner");

  const revoke = useMutation({
    mutationFn: (account: ServiceAccount) => api.serviceAccounts.revoke(account.org, account.id),
    onSuccess: async () => {
      setRevokeTarget(null);
      await queryClient.invalidateQueries({ queryKey: KEY });
      toast.success("Service token revoked");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not revoke token"),
  });

  const columns = useMemo<ColumnDef<ServiceAccount>[]>(
    () => [
      {
        id: "description",
        accessorFn: (account) => `${account.name} ${account.org} ${account.orgDisplayName}`,
        header: sortableHeader("Description"),
        cell: ({ row }) => (
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{row.original.name}</span>
            <Badge variant="outline">#{serviceTokenShortId(row.original.tokenId)}</Badge>
            {row.original.accessProfile === "stack-lifecycle" && (
              <Badge variant="secondary">Create &amp; delete</Badge>
            )}
          </div>
        ),
      },
      {
        accessorKey: "org",
        header: sortableHeader("Organization"),
        cell: ({ row }) => row.original.orgDisplayName,
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => <Status account={row.original} />,
      },
      {
        id: "createdBy",
        header: "Created by",
        cell: ({ row }) => actor(row.original.createdByName, row.original.createdByEmail, row.original.createdById ?? "Unknown"),
      },
      {
        accessorKey: "createdAt",
        header: sortableHeader("Created"),
        cell: ({ row }) => <Timestamp value={row.original.createdAt} />,
      },
      {
        accessorKey: "tokenExpiresAt",
        header: sortableHeader("Expires"),
        cell: ({ row }) => <Expiry value={row.original.tokenExpiresAt} />,
      },
      {
        accessorKey: "tokenLastUsedAt",
        header: sortableHeader("Last used"),
        cell: ({ row }) => row.original.tokenLastUsedAt
          ? <Timestamp value={row.original.tokenLastUsedAt} />
          : <span className="text-muted-foreground">Never</span>,
      },
      {
        id: "actions",
        cell: ({ row }) => serviceAccountStatus(row.original) === "revoked" ? (
          <div className="flex flex-col text-xs text-muted-foreground">
            <span>
              by {actor(row.original.revokedByName, row.original.revokedByEmail, row.original.revokedById ?? "Unknown")}
            </span>
            {row.original.tokenRevokedAt ? <Timestamp value={row.original.tokenRevokedAt} /> : <span>Inactive</span>}
          </div>
        ) : (
          <Button type="button" variant="ghost" size="icon" aria-label={`Revoke ${row.original.name}`} onClick={() => setRevokeTarget(row.original)}>
            <Trash2 />
          </Button>
        ),
      },
    ],
    [],
  );

  const data = useMemo(() => {
    const rows = [...(query.data ?? [])];
    if (filter === "active") {
      return rows.sort((a, b) => {
        const rank = (account: ServiceAccount) => Date.parse(account.tokenExpiresAt);
        return rank(a) - rank(b);
      });
    }
    return rows.sort((a, b) => Date.parse(b.tokenRevokedAt ?? b.createdAt) - Date.parse(a.tokenRevokedAt ?? a.createdAt));
  }, [filter, query.data]);

  const openMint = () => {
    setMintOpen(true);
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Service tokens</h1>
          <p className="text-muted-foreground">Manage org-scoped credentials for CI/CD deployments.</p>
        </div>
        <Button type="button" onClick={openMint} disabled={manageableOrgs.length === 0}>
          <KeyRound data-icon="inline-start" />Mint service token
        </Button>
      </div>

      {query.isLoading ? (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-9 w-72" />
          <Skeleton className="h-72 w-full" />
        </div>
      ) : query.error ? (
        <ErrorAlert error={query.error} onRetry={() => void query.refetch()} />
      ) : allOrgs.error ? (
        <ErrorAlert error={allOrgs.error} onRetry={() => void allOrgs.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          data={data}
          filterColumn="description"
          filterPlaceholder="Search description or organization..."
          emptyTitle={filter === "active" ? "No active service tokens" : "No service tokens"}
          emptyDescription="Mint one credential per CI/CD service when you need it."
          toolbar={
            <Tabs value={filter} onValueChange={(value) => setFilter(value as ServiceAccountFilter)}>
              <TabsList aria-label="Filter service tokens by status">
                <TabsTrigger value="active">Active</TabsTrigger>
                <TabsTrigger value="inactive">Inactive</TabsTrigger>
                <TabsTrigger value="all">All</TabsTrigger>
              </TabsList>
            </Tabs>
          }
        />
      )}

      <MintDialog
        open={mintOpen}
        onOpenChange={setMintOpen}
        organizations={manageableOrgs}
        preferredOrg={selectedOrg}
        activeAccounts={activeQuery.data ?? []}
        onMinted={(value) => {
          setMintOpen(false);
          setMinted(value);
          void queryClient.invalidateQueries({ queryKey: KEY });
        }}
      />

      <AlertDialog open={revokeTarget !== null} onOpenChange={(open) => !open && setRevokeTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Revoke {revokeTarget?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              Prevents new CLI authentication to {revokeTarget?.org} immediately and cannot be undone.
              A deployment already in progress may continue on its existing update lease; use Operations
              → Force unlock only when you intend to interrupt it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction disabled={revoke.isPending} onClick={() => revokeTarget && revoke.mutate(revokeTarget)}>
              Revoke token
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <OneTimeTokenDialog
        rawToken={minted?.token ?? null}
        title="Your service token"
        description="Copy it now and store it in your CI secret manager. statedb cannot show this token again."
        onClose={() => setMinted(null)}
      />
    </div>
  );
}

function Status({ account }: { account: ServiceAccount }) {
  const status = serviceAccountStatus(account);
  if (status === "revoked") {
    return <Badge variant="muted">Revoked</Badge>;
  }
  if (status === "expired") {
    return <Badge variant="destructive">Expired</Badge>;
  }
  return <Badge variant="success">Active</Badge>;
}

function Expiry({ value }: { value: string }) {
  const state = expiryState(value);
  if (state === "expired") return <Badge variant="destructive">Expired {formatDistanceToNow(new Date(value))} ago</Badge>;
  if (state === "expiring") return <Badge variant="warning">Expires in {formatDistanceToNow(new Date(value))}</Badge>;
  return <Timestamp value={value} />;
}

function MintDialog({
  open,
  onOpenChange,
  organizations,
  preferredOrg,
  activeAccounts,
  onMinted,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  organizations: Array<{ name: string; displayName: string }>;
  preferredOrg: string | null;
  activeAccounts: ServiceAccount[];
  onMinted: (value: MintedServiceAccount) => void;
}) {
  const initialOrg = organizations.some((org) => org.name === preferredOrg)
    ? preferredOrg ?? ""
    : organizations.length === 1 ? organizations[0].name : "";
  const [name, setName] = useState("");
  const [org, setOrg] = useState(initialOrg);
  const [expiry, setExpiry] = useState<30 | 90 | 365>(90);
  const [stackLifecycle, setStackLifecycle] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  // Repopulates the org field when the dialog reopens after the user cleared
  // it. Belongs in a remount keyed by `open`; left as an effect for now because
  // the dialog has no component tests to refactor against.
  useEffect(() => {
    if (open && !org) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- see above
      setOrg(initialOrg);
    }
  }, [initialOrg, open, org]);
  const mint = useMutation({
    mutationFn: () => api.serviceAccounts.create(org, {
      name: name.trim(),
      expiresInDays: expiry,
      accessProfile: stackLifecycle ? "stack-lifecycle" : "deploy",
    }),
    onSuccess: (value) => {
      setName("");
      setExpiry(90);
      setStackLifecycle(false);
      setSubmitted(false);
      onMinted(value);
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Could not mint token"),
  });
  const duplicate = activeAccounts.some((account) => account.org === org && account.name === name.trim());
  const invalidName = submitted && !name.trim();
  const invalidOrg = submitted && !org;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (!name.trim() || !org) return;
    mint.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Mint service token</DialogTitle>
          <DialogDescription>
            Creates an org-scoped CI identity. Choose whether it can manage stack lifecycle.
          </DialogDescription>
        </DialogHeader>
        <form id="mint-service-token" onSubmit={submit}>
          <FieldGroup>
            <Field data-invalid={invalidName || undefined}>
              <FieldLabel htmlFor="service-token-description">Description</FieldLabel>
              <Input
                id="service-token-description"
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={200}
                aria-invalid={invalidName || undefined}
                autoFocus
              />
              <FieldDescription>One per service — e.g. github-actions/deploy.</FieldDescription>
              {invalidName && <FieldError>Description is required.</FieldError>}
              {duplicate && <FieldDescription>An active token already uses this description. Duplicates are allowed for rotation.</FieldDescription>}
            </Field>
            <Field data-invalid={invalidOrg || undefined}>
              <FieldLabel htmlFor="service-token-org">Organization</FieldLabel>
              <Select value={org} onValueChange={setOrg}>
                <SelectTrigger id="service-token-org" aria-invalid={invalidOrg || undefined}>
                  <SelectValue placeholder="Select an organization" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {organizations.map((organization) => (
                      <SelectItem key={organization.name} value={organization.name}>
                        {organization.displayName}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              {invalidOrg && <FieldError>Organization is required.</FieldError>}
              {org && <FieldDescription>This credential is worth nothing outside {org}.</FieldDescription>}
            </Field>
            <Field>
              <FieldLabel htmlFor="service-token-expiry">Expiry</FieldLabel>
              <Select value={String(expiry)} onValueChange={(value) => setExpiry(Number(value) as 30 | 90 | 365)}>
                <SelectTrigger id="service-token-expiry"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="30">30 days</SelectItem>
                    <SelectItem value="90">90 days</SelectItem>
                    <SelectItem value="365">365 days</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field orientation="horizontal">
              <Checkbox
                id="service-token-stack-lifecycle"
                checked={stackLifecycle}
                onCheckedChange={(checked) => setStackLifecycle(checked === true)}
              />
              <FieldContent>
                <FieldLabel htmlFor="service-token-stack-lifecycle">
                  Allow this token to create and delete stacks
                </FieldLabel>
                <FieldDescription>
                  Intended for migrations and stack bootstrap. It still cannot rename or retag
                  stacks, manage the organization, or act outside the selected organization.
                </FieldDescription>
              </FieldContent>
            </Field>
          </FieldGroup>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" form="mint-service-token" disabled={mint.isPending}>Mint token for {org || "organization"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
