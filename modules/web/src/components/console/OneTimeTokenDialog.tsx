import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

export function OneTimeTokenDialog({
  rawToken,
  title = "Your CLI token",
  description = "Copy it now. state-db cannot show this token again.",
  onClose,
}: {
  rawToken: string | null;
  title?: string;
  description?: string;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  if (!rawToken) return null;

  const copy = async () => {
    await navigator.clipboard.writeText(rawToken);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <Input readOnly value={rawToken} className="font-mono" aria-label="CLI token" />
          <Button type="button" variant="outline" size="icon" aria-label="Copy token" onClick={() => void copy()}>
            {copied ? <Check data-icon="inline-start" /> : <Copy data-icon="inline-start" />}
          </Button>
        </div>
        <DialogFooter>
          <Button type="button" onClick={onClose}>I&apos;ve copied it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
