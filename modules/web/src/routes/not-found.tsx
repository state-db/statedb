import { Link } from "@tanstack/react-router";

export function NotFoundPage() {
  return (
    <div className="space-y-2">
      <h1 className="text-lg font-semibold">Not found</h1>
      <p className="text-muted-foreground">
        That page doesn&apos;t exist.{" "}
        <Link to="/" className="text-primary underline-offset-4 hover:underline">
          Go home
        </Link>
        .
      </p>
    </div>
  );
}
