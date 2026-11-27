import { Link } from "@tanstack/react-router";
import { Copy, ExternalLink, Terminal } from "lucide-react";
import { toast } from "sonner";

import { EmptyState } from "@/components/console/EmptyState";
import { WorkspaceExplorer } from "@/components/console/WorkspaceExplorer";
import { useShell } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export {
  compareWorkspace,
  hasMultipleKinds,
  isActiveUpdate,
  isFailedUpdate,
  lastUpdateVersionLabel,
  matchesFilter,
  matchesSearch,
  Pagination,
  runningIndicatorLabel,
  StacksTable,
  StacksTableSkeleton,
  summarizeStacks,
  useDebouncedValue,
  useScopedPage,
} from "@/components/console/StacksTable";

export function HomePage() {
  const { selectedOrg } = useShell();
  return <WorkspaceExplorer org={selectedOrg} emptyState={<FirstRunOnboarding />} />;
}

function FirstRunOnboarding() {
  const host =
    typeof window === "undefined" ? "{host}" : window.location.host;
  const origin =
    typeof window === "undefined" ? "https://{host}" : window.location.origin;

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
      <EmptyState
        icon={Terminal}
        title="Connect your Pulumi CLI"
        description="Run one stack against this backend and it will appear here."
        className="border-0"
      />
      <OnboardingStep
        step="1"
        title="Point Pulumi at statedb"
        command={`pulumi login ${origin}/pulumi`}
      />
      <Card>
        <CardHeader>
          <CardTitle>2. Get a CLI token</CardTitle>
          <CardDescription>
            Create a token here, or use the browser flow the CLI opens automatically.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild variant="outline">
            <Link to="/console/account/tokens">
              Open tokens
              <ExternalLink data-icon="inline-end" />
            </Link>
          </Button>
        </CardContent>
      </Card>
      <OnboardingStep
        step="3"
        title="Enable console permalinks"
        command={`export PULUMI_CONSOLE_DOMAIN=${host}`}
        description="Makes pulumi up print links back to this console."
      />
      <OnboardingStep step="4" title="Create or update a stack" command="pulumi up" />
      <p className="text-center text-sm text-muted-foreground">
        OpenTofu support is planned.
      </p>
    </div>
  );
}

function OnboardingStep({
  step,
  title,
  description,
  command,
}: {
  step: string;
  title: string;
  description?: string;
  command: string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {step}. {title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>
        <CopyCommand value={command} />
      </CardContent>
    </Card>
  );
}

function CopyCommand({ value }: { value: string }) {
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      toast("Could not copy to clipboard");
      return;
    }
    toast("Copied");
  }

  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted px-3 py-2">
      <code className="min-w-0 flex-1 truncate font-mono text-sm">{value}</code>
      <Button type="button" variant="ghost" size="icon" onClick={() => void copy()}>
        <Copy />
        <span className="sr-only">Copy command</span>
      </Button>
    </div>
  );
}
