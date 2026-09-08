import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, ShieldAlert } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Timestamp } from "@/components/console/Timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, UnauthorizedError } from "@/lib/api";

export const EULA_KEY = ["admin", "eula"] as const;

export function AdminEulaPage() {
  const queryClient = useQueryClient();
  const [confirmed, setConfirmed] = useState(false);
  const eula = useQuery({
    queryKey: EULA_KEY,
    queryFn: api.admin.eula.get,
    retry: (count, error) => !(error instanceof UnauthorizedError) && count < 2,
  });
  const accept = useMutation({
    mutationFn: (version: string) => api.admin.eula.accept(version),
    onSuccess: (accepted) => {
      queryClient.setQueryData(EULA_KEY, accepted);
      toast.success("License agreement accepted");
    },
    onError: (error: Error) => toast.error(error.message),
  });
  const unauthorized = useUnauthorizedRedirect(eula.error);

  if (unauthorized) return null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">License agreement</h1>
        <p className="text-sm text-muted-foreground">
          A site administrator must accept the End User License Agreement before this
          deployment will create stacks. Acceptance is recorded in this deployment's own
          database.
        </p>
      </div>

      {eula.error ? <ErrorAlert error={eula.error} /> : null}

      {eula.isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : eula.data ? (
        <>
          {eula.data.accepted ? (
            <Alert>
              <CheckCircle2 className="size-4" />
              <AlertTitle>Version {eula.data.version} accepted</AlertTitle>
              <AlertDescription>
                Accepted <Timestamp value={eula.data.acceptedAt ?? ""} />
                {eula.data.acceptedVia === "configuration"
                  ? " by this deployment's configuration"
                  : " in the console"}
                . Stack creation is enabled for this deployment.
              </AlertDescription>
            </Alert>
          ) : (
            <Alert variant="destructive">
              <ShieldAlert className="size-4" />
              <AlertTitle>Stack creation is blocked</AlertTitle>
              <AlertDescription>
                This deployment refuses to create stacks — from the console and from the
                Pulumi CLI alike — until version {eula.data.version} is accepted below.
              </AlertDescription>
            </Alert>
          )}

          <Card>
            <CardHeader>
              <CardTitle>state-db End User License Agreement</CardTitle>
              <CardDescription>
                Version {eula.data.version} · SHA-256 {eula.data.documentSha256.slice(0, 16)}…
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-6">
              <div
                className="max-h-[32rem] overflow-y-auto rounded-md border bg-muted/30 p-4"
                data-testid="eula-text"
              >
                <pre className="whitespace-pre-wrap break-words font-mono text-xs leading-relaxed">
                  {eula.data.text}
                </pre>
              </div>

              {eula.data.accepted ? null : (
                <div className="flex flex-col gap-4">
                  <label className="flex items-start gap-3 text-sm">
                    <Checkbox
                      checked={confirmed}
                      onCheckedChange={(checked) => setConfirmed(checked === true)}
                      aria-label="Confirm authority to accept"
                    />
                    <span className="text-muted-foreground">
                      I have read the agreement and I am authorized to accept it on behalf of
                      the organization operating this deployment.
                    </span>
                  </label>
                  <div>
                    <Button
                      disabled={!confirmed || accept.isPending}
                      onClick={() => accept.mutate(eula.data.version)}
                    >
                      {accept.isPending ? "Accepting…" : "Accept agreement"}
                    </Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      ) : null}
    </div>
  );
}
