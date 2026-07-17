import { formatDistanceToNow } from "date-fns";

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export function Timestamp({ value }: { value: string | Date }) {
  const date = value instanceof Date ? value : new Date(value);
  const iso = Number.isNaN(date.getTime()) ? String(value) : date.toISOString();
  const label = Number.isNaN(date.getTime())
    ? String(value)
    : `${formatDistanceToNow(date, { addSuffix: true })}`;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <time dateTime={iso} className="text-muted-foreground">
          {label}
        </time>
      </TooltipTrigger>
      <TooltipContent>{iso}</TooltipContent>
    </Tooltip>
  );
}
