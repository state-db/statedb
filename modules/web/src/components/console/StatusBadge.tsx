import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export type StackUpdateStatus =
  | "succeeded"
  | "failed"
  | "in-progress"
  | "cancelled"
  | string;

export function StatusBadge({ status }: { status: StackUpdateStatus }) {
  if (status === "succeeded") {
    return <Badge variant="success">succeeded</Badge>;
  }
  if (status === "failed") {
    return <Badge variant="destructive">failed</Badge>;
  }
  if (status === "in-progress") {
    return (
      <Badge variant="info">
        <span className="size-1.5 rounded-full bg-current animate-pulse" />
        in progress
      </Badge>
    );
  }
  if (status === "cancelled") {
    return <Badge variant="muted">cancelled</Badge>;
  }
  return <Badge variant="outline">{status}</Badge>;
}

export type UpdateKind =
  | "update"
  | "preview"
  | "refresh"
  | "destroy"
  | "import"
  | "rename"
  | string;

export function KindBadge({
  kind,
  className,
}: {
  kind: UpdateKind;
  className?: string;
}) {
  return (
    <Badge variant="secondary" className={cn("font-mono", className)}>
      {kind}
    </Badge>
  );
}
