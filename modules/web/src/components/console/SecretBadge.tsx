import { Lock } from "lucide-react";

import { Badge } from "@/components/ui/badge";

export const PULUMI_SECRET_SIG = "4dabf18193072939515e22adb298388d";

export function SecretBadge() {
  return (
    <Badge variant="outline">
      <Lock />
      secret
    </Badge>
  );
}

export function isPulumiSecret(value: unknown): boolean {
  if (!value || typeof value !== "object") {
    return false;
  }
  return Object.prototype.hasOwnProperty.call(value, PULUMI_SECRET_SIG);
}
