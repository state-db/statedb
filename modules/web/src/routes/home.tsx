import { useEffect } from "react";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useCurrentUser } from "@/hooks/useCurrentUser";
import { redirectToLogin, UnauthorizedError } from "@/lib/api";

// First page of the console. Reads GET /api/user: a 200 renders the signed-in
// user, a 401 sends the browser into the OIDC login flow. The SPA holds no
// tokens — the session lives in an HttpOnly cookie the browser never exposes.
export function HomePage() {
  const { data, isLoading, error } = useCurrentUser();

  useEffect(() => {
    if (error instanceof UnauthorizedError) {
      redirectToLogin();
    }
  }, [error]);

  if (error instanceof UnauthorizedError) {
    return <p className="text-muted-foreground">Redirecting to sign in…</p>;
  }

  return (
    <Card className="max-w-md">
      <CardHeader>
        <CardTitle>
          {data ? `Signed in${data.siteAdmin ? " · admin" : ""}` : "Console"}
        </CardTitle>
        <CardDescription>{data?.email ?? "GET /api/user"}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {isLoading && <p className="text-muted-foreground">Loading…</p>}
        {error && (
          <p className="text-destructive">{(error as Error).message}</p>
        )}
        {data && (
          <>
            <div>
              <span className="text-muted-foreground">User </span>
              {data.name ?? data.id}
            </div>
            {data.organizations.length === 0 ? (
              <p className="text-muted-foreground">No organizations</p>
            ) : (
              <ul className="space-y-1">
                {data.organizations.map((o) => (
                  <li key={o.name}>
                    {o.displayName}{" "}
                    <span className="text-muted-foreground">· {o.role}</span>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
