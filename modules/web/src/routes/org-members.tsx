import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { DataTable } from "@/components/console/DataTable";
import { Timestamp } from "@/components/console/Timestamp";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Command, CommandEmpty, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { api, type CurrentUser, type HumanRole, type OrganizationMember } from "@/lib/api";

const roles: HumanRole[] = ["owner", "member", "viewer", "guest"];

export function canManageOrganizationMembers(
  user: Pick<CurrentUser, "siteAdmin" | "organizations">,
  org: string,
) {
  return user.siteAdmin || user.organizations.some(
    (membership) => membership.name === org && membership.role === "owner",
  );
}

export function OrganizationMembersPage({ org }: { org: string }) {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{org}</h1>
        <p className="text-sm text-muted-foreground">Organization members and guest access.</p>
      </div>
      <Members org={org} />
    </div>
  );
}

function Members({ org }: { org: string }) {
  const qc = useQueryClient();
  const key = ["org", org, "members"] as const;
  const q = useQuery({ queryKey: key, queryFn: () => api.orgs.members.list(org) });
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<OrganizationMember | null>(null);
  const owners = (q.data ?? []).filter((member) => member.role === "owner").length;
  const update = useMutation({ mutationFn: ({ id, role }: { id: string; role: HumanRole }) => api.orgs.members.update(org, id, role), onSuccess: () => void qc.invalidateQueries({ queryKey: key }), onError: (error) => toast.error(error.message) });
  const remove = useMutation({ mutationFn: (id: string) => api.orgs.members.remove(org, id), onSuccess: () => { void qc.invalidateQueries({ queryKey: key }); setRemoving(null); toast.success("Member removed"); }, onError: (error) => toast.error(error.message) });
  const columns = useMemo<ColumnDef<OrganizationMember>[]>(() => [
    { id: "user", header: "User", cell: ({ row }) => <div><div>{row.original.displayName ?? row.original.email ?? row.original.userId}</div><div className="text-xs text-muted-foreground">{row.original.email}</div></div> },
    { accessorKey: "role", header: "Role", cell: ({ row }) => <Select value={row.original.role} disabled={row.original.role === "owner" && owners === 1} onValueChange={(role) => update.mutate({ id: row.original.userId, role: role as HumanRole })}><SelectTrigger className="w-32"><SelectValue /></SelectTrigger><SelectContent>{roles.map((role) => <SelectItem key={role} value={role}>{role}</SelectItem>)}</SelectContent></Select> },
    { id: "grants", header: "Granted stacks", cell: ({ row }) => row.original.role !== "guest" ? "—" : row.original.grants.length ? <details><summary>{row.original.grants.length}</summary>{row.original.grants.map((grant) => <div className="text-xs" key={grant.stackId}>{grant.project}/{grant.stack} · {grant.template}</div>)}</details> : "0" },
    { accessorKey: "joinedAt", header: "Joined", cell: ({ row }) => <Timestamp value={row.original.joinedAt} /> },
    { id: "remove", cell: ({ row }) => <Button variant="destructive" size="sm" disabled={row.original.role === "owner" && owners === 1} onClick={() => setRemoving(row.original)}>Remove</Button> },
  ], [owners]);
  return <div>{q.error ? <p className="text-destructive">{q.error.message}</p> : <DataTable columns={columns} data={q.data ?? []} toolbar={<div className="flex justify-end"><Button onClick={() => setAdding(true)}>Add member</Button></div>} />}<AddMember org={org} open={adding} close={() => setAdding(false)} done={() => void qc.invalidateQueries({ queryKey: key })} /><AlertDialog open={!!removing} onOpenChange={(open) => !open && setRemoving(null)}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Remove member?</AlertDialogTitle><AlertDialogDescription>Revokes their access to every stack in {org} on their next CLI request. Their login token remains valid for other organizations.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel>Cancel</AlertDialogCancel><AlertDialogAction onClick={() => removing && remove.mutate(removing.userId)}>Remove</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog></div>;
}

function AddMember({ org, open, close, done }: { org: string; open: boolean; close: () => void; done: () => void }) {
  const q = useQuery({ queryKey: ["org", org, "candidates"], queryFn: () => api.orgs.members.candidates(org), enabled: open });
  const [userId, setUser] = useState("");
  const [search, setSearch] = useState("");
  const [role, setRole] = useState<HumanRole>("member");
  const visible = (q.data ?? []).filter((user) => `${user.email} ${user.displayName}`.toLowerCase().includes(search.toLowerCase()));
  const add = useMutation({ mutationFn: () => api.orgs.members.add(org, userId, role), onSuccess: () => { done(); close(); toast.success("Member added"); }, onError: (error) => toast.error(error.message) });
  return <Dialog open={open} onOpenChange={(nextOpen) => !nextOpen && close()}><DialogContent><DialogHeader><DialogTitle>Add member</DialogTitle></DialogHeader><Command><CommandInput placeholder="Search users by email…" value={search} onChange={(event) => setSearch(event.target.value)} /><CommandList>{visible.length === 0 && <CommandEmpty>No users found.</CommandEmpty>}{visible.map((user) => <CommandItem key={user.id} onClick={() => setUser(user.id)}>{user.email ?? user.displayName}{userId === user.id ? " ✓" : ""}</CommandItem>)}</CommandList></Command><Select value={role} onValueChange={(nextRole) => setRole(nextRole as HumanRole)}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent>{roles.map((option) => <SelectItem key={option} value={option}>{option}</SelectItem>)}</SelectContent></Select><DialogFooter><Button variant="outline" onClick={close}>Cancel</Button><Button disabled={!userId || add.isPending} onClick={() => add.mutate()}>Add</Button></DialogFooter></DialogContent></Dialog>;
}
