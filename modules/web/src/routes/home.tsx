import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useMe } from "@/hooks/useMe";

// First page of the console. Exists to prove the full pipeline end to end:
// React → TanStack Query → /api/me → Quarkus (proxied in dev, same-origin in
// prod). Replace with real console content as Wave 2 fills in.
export function HomePage() {
  const { data, isLoading, error } = useMe();

  return (
    <Card className="max-w-md">
      <CardHeader>
        <CardTitle>Console is live</CardTitle>
        <CardDescription>Response from GET /api/me</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading && <p className="text-muted-foreground">Loading…</p>}
        {error && (
          <p className="text-destructive">{(error as Error).message}</p>
        )}
        {data && (
          <pre className="rounded-md bg-muted p-3 text-sm">
            {JSON.stringify(data, null, 2)}
          </pre>
        )}
      </CardContent>
    </Card>
  );
}
