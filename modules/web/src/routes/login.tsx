import { FormEvent, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertCircle, Database, Loader2 } from "lucide-react";

import { ErrorAlert } from "@/components/console/ErrorAlert";
import {
  Alert,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

export function LoginPage() {
  const searchParams = useMemo(
    () => new URLSearchParams(window.location.search),
    [],
  );
  const returnTo = searchParams.get("returnTo") ?? "/console";
  const failedProvider =
    searchParams.get("provider") ??
    searchParams.get("errorProvider") ??
    searchParams.get("error_provider");

  const providersQuery = useQuery({
    queryKey: ["auth", "providers"],
    queryFn: api.auth.providers.list,
  });

  return (
    <main className="flex min-h-full items-center justify-center bg-background p-6">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <div className="mb-2 flex h-10 w-10 items-center justify-center rounded-md border bg-muted">
            <Database className="h-5 w-5" aria-hidden="true" />
          </div>
          <div className="text-sm font-semibold text-muted-foreground">
            state-db
          </div>
          <CardTitle className="text-xl">Sign in to state-db</CardTitle>
          <CardDescription>
            Use your configured identity provider to open the console.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {failedProvider && (
            <Alert variant="destructive">
              <AlertCircle />
              <AlertTitle>Sign-in failed</AlertTitle>
              <AlertDescription>
                Sign-in failed at {failedProvider}.
              </AlertDescription>
            </Alert>
          )}

          {providersQuery.isLoading && <ProviderSkeleton />}

          {providersQuery.isError && (
            <ErrorAlert
              title="Could not load sign-in providers"
              error={providersQuery.error}
              onRetry={() => void providersQuery.refetch()}
            />
          )}

          {providersQuery.isSuccess &&
            (providersQuery.data.providers.length > 0 ? (
              <div className="space-y-2">
                {providersQuery.data.providers.map((provider) => (
                  <Button
                    key={provider.name}
                    asChild
                    variant="outline"
                    className="w-full justify-center"
                  >
                    <a href={providerLoginUrl(provider.name, returnTo)}>
                      {provider.displayName || provider.name}
                    </a>
                  </Button>
                ))}
              </div>
            ) : providersQuery.data.breakGlass ? (
              <BreakGlassForm returnTo={returnTo} />
            ) : (
              <NoSignInMethods />
            ))}
        </CardContent>
      </Card>
    </main>
  );
}

function ProviderSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-9 w-full" />
      <Skeleton className="h-9 w-full" />
    </div>
  );
}

function NoSignInMethods() {
  return (
    <Alert>
      <AlertCircle />
      <AlertTitle>No sign-in methods available</AlertTitle>
      <AlertDescription>
        Configure an OIDC provider to sign in, or set
        STATE_DB_LOCAL_GLASS_PASSWORD_HASH to enable break-glass access.
      </AlertDescription>
    </Alert>
  );
}

function BreakGlassForm({ returnTo }: { returnTo: string }) {
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const password = new FormData(event.currentTarget).get("password");
    if (typeof password !== "string" || password.length === 0) {
      return;
    }
    setSubmitting(true);
    setFailed(false);
    try {
      await api.auth.breakGlass(password);
      window.location.assign(safeReturnTo(returnTo));
    } catch {
      setFailed(true);
      setSubmitting(false);
    }
  }

  return (
    <form className="space-y-4" onSubmit={(event) => void onSubmit(event)}>
      <Alert>
        <AlertCircle />
        <AlertTitle>No OIDC providers are enabled</AlertTitle>
        <AlertDescription>
          Break-glass sign-in accepts the operator password from localhost only,
          unless remote access was explicitly allowed at deploy time.
        </AlertDescription>
      </Alert>

      {failed && (
        <Alert variant="destructive">
          <AlertCircle />
          <AlertTitle>Sign-in failed</AlertTitle>
          <AlertDescription>
            Wrong password, or break-glass is not available from this address.
          </AlertDescription>
        </Alert>
      )}

      <div className="space-y-2">
        <Label htmlFor="break-glass-password">Password</Label>
        <Input
          id="break-glass-password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
        />
      </div>
      <Button type="submit" className="w-full" disabled={submitting}>
        {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
        Sign in
      </Button>
    </form>
  );
}

function safeReturnTo(value: string) {
  return value.startsWith("/") && !value.startsWith("//") ? value : "/console";
}

function providerLoginUrl(provider: string, returnTo: string) {
  const params = new URLSearchParams({ returnTo });
  return `/api/auth/login/${encodeURIComponent(provider)}?${params}`;
}
