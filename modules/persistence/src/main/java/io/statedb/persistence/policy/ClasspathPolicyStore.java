package io.statedb.persistence.policy;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@ApplicationScoped
public class ClasspathPolicyStore implements PolicyStore {

  private static final List<String> POLICY_RESOURCES =
      List.of(
          "policies/admin-escape.cedar",
          "policies/org-members-can-read.cedar",
          "policies/org-write.cedar");

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
