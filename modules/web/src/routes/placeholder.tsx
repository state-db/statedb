import type { LucideIcon } from "lucide-react";

import { EmptyState } from "@/components/console/EmptyState";

export function PlaceholderPage({
  title,
  description,
  icon,
}: {
  title: string;
  description: string;
  icon?: LucideIcon;
}) {
  return <EmptyState title={title} description={description} icon={icon} />;
}
