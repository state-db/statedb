import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { DataTable, sortableHeader } from "@/components/console/DataTable";
import { Timestamp } from "@/components/console/Timestamp";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { api, type AdminOrganization } from "@/lib/api";

const KEY = ["admin", "orgs"] as const;
const RESERVED = new Set(["api","pulumi","tofu","console","assets","cli-login","welcome","account","user","favicon.ico"]);
const GRAMMAR = /^[A-Za-z0-9._-]{1,100}$/;

export function AdminOrganizationsPage() {
  const qc = useQueryClient(); const query = useQuery({ queryKey: KEY, queryFn: api.admin.orgs.list });
  const [creating, setCreating] = useState(false); const [deleting, setDeleting] = useState<AdminOrganization | null>(null);
  const del = useMutation({ mutationFn: (id: string) => api.admin.orgs.delete(id), onSuccess: () => { void qc.invalidateQueries({queryKey: KEY}); setDeleting(null); toast.success("Organization deleted"); }, onError: e => toast.error(e.message) });
  const columns = useMemo<ColumnDef<AdminOrganization>[]>(() => [
    {accessorKey:"name", header:sortableHeader("Name")}, {accessorKey:"displayName", header:"Display name"},
    {accessorKey:"members", header:"Members", cell:({row}) => <Link className="underline" to="/console/admin/orgs/$org" params={{org:row.original.name}}>{row.original.members}</Link>},
    {accessorKey:"stacks", header:"Stacks"}, {accessorKey:"createdAt", header:"Created", cell:({row})=><Timestamp value={row.original.createdAt}/>},
    {id:"delete", cell:({row}) => row.original.stacks ? <Tooltip><TooltipTrigger asChild><span><Button variant="destructive" size="sm" disabled>Delete</Button></span></TooltipTrigger><TooltipContent>Delete every stack first.</TooltipContent></Tooltip> : <Button variant="destructive" size="sm" onClick={()=>setDeleting(row.original)}>Delete</Button>}
  ], []);
  return <div className="flex flex-col gap-6"><div><h1 className="text-2xl font-semibold">Organizations</h1><p className="text-sm text-muted-foreground">Create and retire empty tenants.</p></div>
    <div>{query.error ? <p className="text-destructive">{query.error.message}</p> : <DataTable columns={columns} data={query.data ?? []} toolbar={<div className="flex justify-end"><Button onClick={()=>setCreating(true)}><Plus/>Create organization</Button></div>}/>}</div>
    <CreateOrg open={creating} close={()=>setCreating(false)}/><DeleteOrg org={deleting} close={()=>setDeleting(null)} submit={()=>deleting && del.mutate(deleting.id)}/></div>;
}

function CreateOrg({open,close}:{open:boolean;close:()=>void}) { const qc=useQueryClient(); const [name,setName]=useState(""); const [displayName,setDisplay]=useState(""); const valid=GRAMMAR.test(name)&&!RESERVED.has(name.toLowerCase())&&displayName.trim().length>0; const mutation=useMutation({mutationFn:()=>api.admin.orgs.create({name,displayName}),onSuccess:()=>{void qc.invalidateQueries({queryKey:KEY});close();setName("");setDisplay("");toast.success("Organization created")},onError:e=>toast.error(e.message)}); return <Dialog open={open} onOpenChange={v=>!v&&close()}><DialogContent><DialogHeader><DialogTitle>Create organization</DialogTitle></DialogHeader><Input placeholder="Name" value={name} onChange={e=>setName(e.target.value)}/><Input placeholder="Display name" value={displayName} onChange={e=>setDisplay(e.target.value)}/>{name&&!valid&&<p className="text-sm text-destructive">Use 1–100 letters, numbers, dots, underscores, or hyphens and avoid reserved names.</p>}<DialogFooter><Button variant="outline" onClick={close}>Cancel</Button><Button disabled={!valid||mutation.isPending} onClick={()=>mutation.mutate()}>Create</Button></DialogFooter></DialogContent></Dialog> }
function DeleteOrg({org,close,submit}:{org:AdminOrganization|null;close:()=>void;submit:()=>void}) { const [confirm,setConfirm]=useState(""); return <AlertDialog open={!!org} onOpenChange={v=>{if(!v){close();setConfirm("")}}}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Delete organization?</AlertDialogTitle><AlertDialogDescription>This permanently deletes the organization, its memberships, and all service tokens. Type {org?.name} to confirm.</AlertDialogDescription></AlertDialogHeader><Input value={confirm} onChange={e=>setConfirm(e.target.value)}/><AlertDialogFooter><AlertDialogCancel>Cancel</AlertDialogCancel><AlertDialogAction disabled={confirm!==org?.name} onClick={submit}>Delete</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog> }
