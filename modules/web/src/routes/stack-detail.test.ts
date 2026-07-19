import { describe, expect, test } from "bun:test";

import { PULUMI_SECRET_SIG } from "@/components/console/SecretBadge";
import {
  containsSecret,
  maskSecrets,
  resourceName,
  resourceType,
  serializeOutput,
  stackOutputs,
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

  test("reads outputs from the synthetic stack resource", () => {
    const outputs = { endpoint: "https://example.test", replicas: 3 };
    expect(stackOutputs([
      { urn: "urn:pulumi:dev::app::random:RandomPet::pet", outputs: { id: "fox" } },
      {
        urn: "urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev",
        outputs,
      },
    ])).toEqual(outputs);
  });

  test("serializes object outputs as compact raw JSON for copying", () => {
    expect(serializeOutput({ endpoint: "api", ports: [80, 443] })).toBe(
      '{"endpoint":"api","ports":[80,443]}',
    );
    expect(serializeOutput(42)).toBe("42");
  });
});
