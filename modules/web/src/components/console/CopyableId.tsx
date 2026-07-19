import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function CopyableId({
  value,
  className,
}: {
  value: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    toast("Copied");
    window.setTimeout(() => setCopied(false), 1200);
  }

  return (
    <span className={cn("inline-flex min-w-0 items-center gap-1", className)}>
      <code className="min-w-0 flex-1 truncate font-mono text-xs" title={value}>
        {value}
      </code>
      <Button type="button" variant="ghost" size="icon" onClick={copy}>
        {copied ? <Check /> : <Copy />}
        <span className="sr-only">Copy identifier</span>
      </Button>
    </span>
  );
}
