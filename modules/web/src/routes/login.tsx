import { LogIn } from "lucide-react";

import { EmptyState } from "@/components/console/EmptyState";
import { Button } from "@/components/ui/button";

export function LoginPage() {
  const params = new URLSearchParams(window.location.search);
  const returnTo = params.get("returnTo") ?? "/console";
  const loginUrl = `/api/auth/login?returnTo=${encodeURIComponent(returnTo)}`;

  return (
    <main className="flex min-h-full items-center justify-center p-6">
      <EmptyState
        icon={LogIn}
        title="Sign in to state-db"
        description="The provider chooser lands here in the next slice. Use the server-owned login route for now."
        action={
          <Button asChild>
            <a href={loginUrl}>Sign in</a>
          </Button>
        }
        className="w-full max-w-md border-solid"
      />
    </main>
  );
}
