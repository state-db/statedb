import { Lock } from "lucide-react";

import { Badge } from "@/components/ui/badge";

export { isPulumiSecret, PULUMI_SECRET_SIG, PULUMI_SIG_KEY } from "@/lib/pulumi-secrets";

export function SecretBadge() {
  return (
    <Badge variant="outline">
      <Lock />
      secret
    </Badge>
  );
}
