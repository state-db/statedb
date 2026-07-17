import { Link } from "@tanstack/react-router";

import { EmptyState } from "@/components/console/EmptyState";
import { Button } from "@/components/ui/button";

export function NotFoundPage() {
  return (
    <main className="p-6">
      <EmptyState
        title="Not found"
        description="That page does not exist."
        action={
          <Button asChild variant="outline">
            <Link to="/console">Go home</Link>
          </Button>
        }
      />
    </main>
  );
}
