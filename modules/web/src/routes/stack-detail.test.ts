import { describe, expect, test } from "bun:test";

import { PULUMI_SECRET_SIG } from "@/components/console/SecretBadge";
import {
  containsSecret,
  maskSecrets,
  resourceName,
  resourceType,
} from "@/routes/stack-detail";

describe("stack resource presentation", () => {
  test("derives the resource name and type from a URN", () => {
    const resource = {
      urn: "urn:pulumi:prod::networking::aws:s3/bucket:Bucket::site-assets",
    };

    expect(resourceName(resource)).toBe("site-assets");
    expect(resourceType(resource)).toBe("aws:s3/bucket:Bucket");
  });

  test("masks nested secrets without leaking ciphertext", () => {
    const value = {
      public: "visible",
      nested: {
        [PULUMI_SECRET_SIG]: "1",
        ciphertext: "do-not-render",
      },
    };

    expect(containsSecret(value)).toBe(true);
    expect(maskSecrets(value)).toEqual({ public: "visible", nested: "[secret]" });
    expect(JSON.stringify(maskSecrets(value))).not.toContain("do-not-render");
  });
});
