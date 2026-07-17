import { Database } from "lucide-react";

import { EmptyState } from "@/components/console/EmptyState";
import { useShell } from "@/components/shell/AppShell";

export function HomePage() {
  const { selectedOrg } = useShell();

  return (
    <EmptyState
      icon={Database}
      title={selectedOrg ? `${selectedOrg} stacks` : "Stacks"}
      description="Stack browsing fills in here in S2. The shell, auth gate, and org filter are ready."
    />
  );
}
