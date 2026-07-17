import { AlertCircle } from "lucide-react";

import {
  Alert,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

export function ErrorAlert({
  title = "Something went wrong",
  error,
  onRetry,
}: {
  title?: string;
  error: unknown;
  onRetry?: () => void;
}) {
  return (
    <Alert variant="destructive">
      <AlertCircle />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription className="flex items-center justify-between gap-3">
        <span>{error instanceof Error ? error.message : String(error)}</span>
        {onRetry && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
