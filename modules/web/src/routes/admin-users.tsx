import { useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { DataTable, sortableHeader } from "@/components/console/DataTable";
import { CopyableId } from "@/components/console/CopyableId";
import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Timestamp } from "@/components/console/Timestamp";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { api, type AdminUser, type AdminUserOrg } from "@/lib/api";

const KEY = ["admin", "users"] as const;

export type UserFilter = "human" | "service" | "all";

/** The canonical `<iss>:<sub>` identity — the exact string an operator pastes into STATE_DB_ADMIN_SUBJECTS. */
export function identityString(user: Pick<AdminUser, "iss" | "sub">): string {
  return `${user.iss}:${user.sub}`;
}

export function filterUsers(users: AdminUser[], filter: UserFilter): AdminUser[] {
  return filter === "all" ? users : users.filter((u) => u.principalType === filter);
}

/**
 * Orgs offered as "Manage memberships" targets. Service accounts are owned by the Service tokens
 * screen (S13) and excluded from org member management (S17), so they surface no target.
 */
export function manageOrgs(user: AdminUser): AdminUserOrg[] {
  return user.principalType === "human" ? user.organizations : [];
}

function initial(user: AdminUser): string {
  const source = user.displayName ?? user.email ?? user.sub;
  return source.trim().charAt(0).toUpperCase() || "?";
}

export function AdminUsersPage() {
  const [page, setPage] = useState(1);
  const [showService, setShowService] = useState(false);
  const filter: UserFilter = showService ? "all" : "human";
  const query = useQuery({
    queryKey: [...KEY, page, showService],
    queryFn: () => api.admin.users.list(page, showService),
  });

  const columns = useMemo<ColumnDef<AdminUser>[]>(
    () => [
      {
        id: "user",
        header: sortableHeader("User"),
        accessorFn: (u) => u.displayName ?? u.email ?? u.sub,
        cell: ({ row }) => {
          const u = row.original;
          return (
            <div className="flex items-center gap-3">
              <Avatar className="size-8">
                <AvatarFallback>{initial(u)}</AvatarFallback>
              </Avatar>
              <div className="min-w-0">
                <div className="flex items-center gap-2 truncate font-medium">
                  {u.displayName ?? "—"}
                  {u.siteAdmin && (
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="inline-flex">
                          <Badge variant="outline">admin</Badge>
                        </span>
                      </TooltipTrigger>
                      <TooltipContent>Site admin — listed in STATE_DB_ADMIN_SUBJECTS</TooltipContent>
                    </Tooltip>
                  )}
                </div>
                <div className="truncate text-xs text-muted-foreground">{u.email ?? "no email"}</div>
              </div>
            </div>
          );
        },
      },
      {
        id: "identity",
        header: "Identity",
        cell: ({ row }) => <CopyableId value={identityString(row.original)} className="max-w-xs" />,
      },
      {
        id: "orgs",
        header: "Organizations",
        cell: ({ row }) =>
          row.original.organizations.length ? (
            <div className="flex flex-wrap gap-1">
              {row.original.organizations.map((o) => (
                <Badge key={o.name} variant="secondary">
                  {o.name} · {o.role}
                </Badge>
              ))}
            </div>
          ) : (
            <span className="text-xs text-muted-foreground">—</span>
          ),
      },
      {
        id: "lastLogin",
        header: sortableHeader("Last login"),
        accessorFn: (u) => u.lastLoginAt ?? "",
        cell: ({ row }) =>
          row.original.lastLoginAt ? (
            <Timestamp value={row.original.lastLoginAt} />
          ) : (
            <span className="text-xs text-muted-foreground">Never</span>
          ),
      },
      {
        accessorKey: "createdAt",
        header: sortableHeader("Created"),
        cell: ({ row }) => <Timestamp value={row.original.createdAt} />,
      },
      {
        id: "manage",
        cell: ({ row }) => <ManageMemberships user={row.original} />,
      },
    ],
    [],
  );

  const data = useMemo(
    () => filterUsers(query.data?.users ?? [], filter),
    [query.data, filter],
  );
  const pageCount = query.data
    ? Math.max(1, Math.ceil(query.data.total / query.data.pageSize))
    : 1;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Users</h1>
        <p className="text-sm text-muted-foreground">
          Everyone who has signed in, plus service identities.
        </p>
      </div>
      {query.error ? (
        <ErrorAlert error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          data={data}
          emptyTitle="No users"
          emptyDescription="No identities match this filter yet."
          toolbar={
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Switch
                  id="show-service"
                  checked={showService}
                  onCheckedChange={(checked) => {
                    setShowService(checked);
                    setPage(1);
                  }}
                />
                <Label htmlFor="show-service" className="text-sm text-muted-foreground">
                  Show service accounts
                </Label>
              </div>
              <span className="text-sm text-muted-foreground">
                {query.data?.total ?? 0}{" "}
                {query.data?.total === 1 ? "identity" : "identities"}
              </span>
            </div>
          }
          pagination={{ page, pageCount, onPageChange: setPage }}
        />
      )}
    </div>
  );
}

function ManageMemberships({ user }: { user: AdminUser }) {
  const orgs = manageOrgs(user);
  if (orgs.length === 0) return null;
  if (orgs.length === 1) {
    return (
      <Button asChild variant="ghost" size="sm">
        <Link to="/console/admin/orgs/$org" params={{ org: orgs[0].name }}>
          Manage memberships
        </Link>
      </Button>
    );
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm">
          Manage memberships
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {orgs.map((o) => (
          <DropdownMenuItem key={o.name} asChild>
            <Link to="/console/admin/orgs/$org" params={{ org: o.name }}>
              {o.name}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
