import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function CopyableId({
  value,
  className,
  buttonClassName,
}: {
  value: string;
  className?: string;
  buttonClassName?: string;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      toast("Could not copy to clipboard");
      return;
    }
    setCopied(true);
    toast("Copied");
    window.setTimeout(() => setCopied(false), 1200);
  }

  return (
    <span className={cn("inline-flex min-w-0 items-center gap-1", className)}>
      {/* Truncate from the start: for path-like identifiers (URNs, ARNs) the
          distinguishing suffix (resource type::name) matters more than the
          shared prefix, so keep the end visible instead of the beginning. */}
      <code className="min-w-0 flex-1 truncate text-left font-mono text-xs" dir="rtl" title={value}>
        {value}
      </code>
      <Button type="button" variant="ghost" size="icon" className={buttonClassName} onClick={() => void copy()}>
        {copied ? <Check /> : <Copy />}
        <span className="sr-only">Copy identifier</span>
      </Button>
    </span>
  );
}
