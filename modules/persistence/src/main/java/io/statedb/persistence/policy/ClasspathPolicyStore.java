package io.statedb.persistence.policy;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ClasspathPolicyStore implements PolicyStore {

  private static final List<String> POLICY_RESOURCES =
      List.of(
          "policies/admin.cedar",
          "policies/owner.cedar",
          "policies/member.cedar",
          "policies/viewer.cedar",
          "policies/service.cedar",
          "policies/guardrails.cedar");

  // Template id -> classpath resource. The id is what a policy_grants row and the resulting
  // TemplateLink reference, so it must stay stable.
  private static final Map<String, String> TEMPLATE_RESOURCES =
      Map.of(
          "StackWriteAccess", "policies/templates/StackWriteAccess.cedar",
          "StackReadResourcePolicy", "policies/templates/StackReadResourcePolicy.cedar",
          "StackWriteResourcePolicy", "policies/templates/StackWriteResourcePolicy.cedar");

  @Override
  public String schema() {
    return read("policies/schema.cedar");
  }

  @Override
  public String policies() {
    StringBuilder all = new StringBuilder();
    for (String resource : POLICY_RESOURCES) {
      all.append(read(resource)).append('\n');
    }
    return all.toString();
  }

  @Override
  public Map<String, String> templates() {
    Map<String, String> templates = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : TEMPLATE_RESOURCES.entrySet()) {
      templates.put(e.getKey(), read(e.getValue()));
    }
    return templates;
  }

  private static String read(String resource) {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing classpath resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("failed reading " + resource, e);
    }
  }
}
