package app.statedb.persistence.authz;

import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The path values selected by Jakarta REST for an authorized resource method. */
public final class AuthorizationPath {

  private final Map<String, List<String>> parameters;
  private final String endpoint;
  private final String loader;

  AuthorizationPath(
      MultivaluedMap<String, String> parameters,
      Method endpoint,
      Class<? extends EntityLoader<?>> loader) {
    this.parameters =
        parameters.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    this.endpoint = endpoint.getDeclaringClass().getName() + "#" + endpoint.getName();
    this.loader = loader.getName();
  }

  public String org() {
    return required("org");
  }

  public String project() {
    return required("project");
  }

  public String stack() {
    return required("stack");
  }

  private String required(String variable) {
    List<String> values = parameters.get(variable);
    if (values == null || values.isEmpty()) {
      throw invalid(variable, "is missing");
    }
    if (values.size() != 1) {
      throw invalid(variable, "is ambiguous (" + values.size() + " values)");
    }
    if (values.getFirst() == null || values.getFirst().isBlank()) {
      throw invalid(variable, "is missing");
    }
    return values.getFirst();
  }

  private IllegalStateException invalid(String variable, String reason) {
    return new IllegalStateException(
        "Invalid @Authorize route binding: endpoint "
            + endpoint
            + ", loader "
            + loader
            + ", required URI variable {"
            + variable
            + "} "
            + reason
            + ", available URI variables "
            + new TreeSet<>(parameters.keySet()));
  }
}
