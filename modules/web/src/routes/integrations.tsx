import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLink, GitPullRequest, Unplug } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useShell } from "@/components/shell/AppShell";
import { api, type CurrentUser } from "@/lib/api";

type IntegrationUser = Pick<CurrentUser, "siteAdmin" | "organizations">;

export function canManageIntegrations(user: IntegrationUser) {
  return user.siteAdmin || user.organizations.some((org) => org.role === "owner");
}

export function canManageOrg(user: IntegrationUser, name: string | null) {
  return Boolean(name && (user.siteAdmin || user.organizations.some((org) => org.name === name && org.role === "owner")));
}

const SETUP_DISABLED = "Set STATEDB_REPORTS_PUBLIC_BASE_URL and restart state-db to connect GitHub.";

export function connectionNotice(result?: string) {
  switch (result) {
    case "connected": return "GitHub connected. Future completed runs can publish PR reports.";
    case "installation-updated": return "GitHub installation updated. Existing connections are checked below; select Connect GitHub to link a new installation.";
    case "registered": return "GitHub App created. Connect an organization below.";
    case "denied": return "GitHub authorization was cancelled. Nothing was connected.";
    case "installation-pending": return "The GitHub installation needs approval. After approval, select Connect GitHub again.";
    case "restart": return "The GitHub App changed during setup. Select Connect GitHub again.";
    case "verification-failed": return "Could not verify GitHub access. Use an account with access to the installation and select Connect GitHub again.";
    default: return null;
  }
}

export function IntegrationsPage({ github, org }: { github?: string; org?: string }) {
  const { user, selectedOrg, setSelectedOrg } = useShell();
  const queryClient = useQueryClient();
  const [githubOrganization, setGithubOrganization] = useState("");
  const restoredOrg = useRef(false);
  useEffect(() => {
    if (!restoredOrg.current && org && canManageOrg(user, org)) {
      restoredOrg.current = true;
      setSelectedOrg(org);
    }
  }, [org, user, setSelectedOrg]);
  const allowed = canManageOrg(user, selectedOrg);
  const appQuery = useQuery({
    queryKey: ["github-app", user.siteAdmin ? "site" : selectedOrg],
    queryFn: () => user.siteAdmin ? api.admin.github.status() : api.orgs.vcs.app(selectedOrg!),
    enabled: user.siteAdmin || allowed,
  });
  const connectionsQuery = useQuery({
    queryKey: ["vcs-connections", selectedOrg],
    queryFn: () => api.orgs.vcs.connections(selectedOrg!),
    enabled: allowed && Boolean(appQuery.data?.configured),
  });
  const createApp = useMutation({
    mutationFn: () => api.admin.github.manifest(githubOrganization),
    onSuccess: submitManifest,
    onError: (error) => toast.error(errorMessage(error)),
  });
  const connect = useMutation({
    mutationFn: (organization: string) => api.orgs.vcs.connect(organization),
    onSuccess: ({ url }) => window.location.assign(url),
    onError: (error) => toast.error(errorMessage(error)),
  });
  const disconnect = useMutation({
    mutationFn: (organization: string) => api.orgs.vcs.disconnect(organization),
    onSuccess: (_, organization) => {
      void queryClient.invalidateQueries({ queryKey: ["vcs-connections", organization] });
      toast.success(`GitHub disconnected from ${organization}`);
    },
    onError: (error) => toast.error(errorMessage(error)),
  });
  const app = appQuery.data;
  const connection = connectionsQuery.data?.find((c) => c.enabled);
  const notice = connectionNotice(github);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Integrations</h1>
        <p className="text-sm text-muted-foreground">Connect GitHub pull requests to state-db previews.</p>
      </div>
      {notice && <Alert><AlertTitle>GitHub setup{org ? ` · ${org}` : ""}</AlertTitle><AlertDescription>{notice}</AlertDescription></Alert>}
      {appQuery.error ? (
        <ErrorAlert title="Could not load the GitHub App" error={appQuery.error} onRetry={() => void appQuery.refetch()} />
      ) : appQuery.isLoading ? <Skeleton className="h-56 w-full" /> : app && !app.setupEnabled && !app.configured ? (
        <Alert><AlertTitle>GitHub setup is disabled</AlertTitle><AlertDescription>{SETUP_DISABLED}</AlertDescription></Alert>
      ) : !app?.configured ? (
        <Card>
          <CardHeader><CardTitle>Set up the GitHub App</CardTitle><CardDescription>Create one App for this state-db deployment.</CardDescription></CardHeader>
          <CardContent>
            {user.siteAdmin ? <FieldGroup><Field>
              <FieldLabel htmlFor="github-organization">GitHub organization</FieldLabel>
              <Input id="github-organization" value={githubOrganization} onChange={(e) => setGithubOrganization(e.target.value)} placeholder="acme" autoComplete="off" />
              <FieldDescription>Leave blank to create the App under your personal GitHub account.</FieldDescription>
            </Field></FieldGroup> : <p>A site administrator must create the GitHub App first.</p>}
          </CardContent>
          {user.siteAdmin && <CardFooter><Button disabled={createApp.isPending} onClick={() => createApp.mutate()}><GitPullRequest data-icon="inline-start" />Create GitHub App</Button></CardFooter>}
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>GitHub{selectedOrg ? ` · ${selectedOrg}` : ""}</CardTitle>
            <CardDescription>App: {app.slug}. Choose repository access on GitHub; state-db completes the connection when you return.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {!allowed ? <p>Select an organization you own to connect GitHub.</p>
              : connectionsQuery.isLoading ? <Skeleton className="h-20 w-full" />
              : connectionsQuery.error ? <ErrorAlert title="Could not load the connection" error={connectionsQuery.error} onRetry={() => void connectionsQuery.refetch()} />
              : connection ? <>
                <Badge variant={connection.error ? "secondary" : "default"}>{connection.error ? "Needs attention" : "Connected"}</Badge>
                {connection.error ? <Alert><AlertTitle>Check GitHub access</AlertTitle><AlertDescription>{connection.error}</AlertDescription></Alert> : <>
                  <p>Account: {connection.accountLogin}</p>
                  <p>Access: {connection.repositorySelection === "all" ? "All repositories" : "Selected repositories"}</p>
                </>}
              </> : <p>No GitHub account connected.{app.setupEnabled ? "" : ` ${SETUP_DISABLED}`}</p>}
          </CardContent>
          <CardFooter className="flex flex-wrap gap-2">
            {allowed && app.setupEnabled && (!connection || connection.error) && <Button disabled={connect.isPending || disconnect.isPending || connectionsQuery.isLoading} onClick={() => connect.mutate(selectedOrg!)}><GitPullRequest data-icon="inline-start" />{connection ? "Reconnect GitHub" : "Connect GitHub"}</Button>}
            {connection?.managementUrl && <Button asChild variant="outline"><a href={connection.managementUrl} target="_blank" rel="noreferrer">Manage repositories<ExternalLink data-icon="inline-end" /></a></Button>}
            {allowed && connection && <Button variant="outline" disabled={disconnect.isPending || connect.isPending} onClick={() => disconnect.mutate(selectedOrg!)}><Unplug data-icon="inline-start" />Disconnect</Button>}
            {user.siteAdmin && app.htmlUrl && <Button asChild variant="ghost"><a href={app.htmlUrl} target="_blank" rel="noreferrer">View App<ExternalLink data-icon="inline-end" /></a></Button>}
          </CardFooter>
        </Card>
      )}
    </div>
  );
}

function submitManifest(submission: { actionUrl: string; manifest: string }) {
  const form = document.createElement("form");
  form.method = "post";
  form.action = submission.actionUrl;
  const manifest = document.createElement("input");
  manifest.type = "hidden";
  manifest.name = "manifest";
  manifest.value = submission.manifest;
  form.append(manifest);
  document.body.append(form);
  form.submit();
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Request failed";
}
