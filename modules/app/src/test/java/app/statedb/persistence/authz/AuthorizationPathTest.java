package app.statedb.persistence.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MultivaluedHashMap;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AuthorizationPathTest {

  private static final Method ENDPOINT;

  static {
    try {
      ENDPOINT = EndpointWithoutArguments.class.getDeclaredMethod("get");
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Test
  void readsRouterValuesWithoutEndpointArguments() {
    MultivaluedHashMap<String, String> values = new MultivaluedHashMap<>();
    values.add("org", "acme");
    values.add("project", "infra");
    values.add("stack", "prod");

    AuthorizationPath path = new AuthorizationPath(values, ENDPOINT, StackEntityLoader.class);

    assertEquals("acme", path.org());
    assertEquals("infra", path.project());
    assertEquals("prod", path.stack());
  }

  @Test
  void missingVariableNamesEndpointLoaderAndAvailableVariables() {
    MultivaluedHashMap<String, String> values = new MultivaluedHashMap<>();
    values.add("project", "infra");
    values.add("stack", "prod");
    AuthorizationPath path = new AuthorizationPath(values, ENDPOINT, StackEntityLoader.class);

    IllegalStateException failure = assertThrows(IllegalStateException.class, path::org);

    assertTrue(failure.getMessage().contains("EndpointWithoutArguments#get"));
    assertTrue(failure.getMessage().contains(StackEntityLoader.class.getName()));
    assertTrue(failure.getMessage().contains("required URI variable {org} is missing"));
    assertTrue(failure.getMessage().contains("[project, stack]"));
  }

  @Test
  void duplicatedVariableIsRejectedAsAmbiguous() {
    MultivaluedHashMap<String, String> values = new MultivaluedHashMap<>();
    values.add("org", "first");
    values.add("org", "second");
    AuthorizationPath path = new AuthorizationPath(values, ENDPOINT, OrgEntityLoader.class);

    IllegalStateException failure = assertThrows(IllegalStateException.class, path::org);

    assertTrue(failure.getMessage().contains(OrgEntityLoader.class.getName()));
    assertTrue(
        failure.getMessage().contains("required URI variable {org} is ambiguous (2 values)"));
  }

  private static final class EndpointWithoutArguments {
    void get() {}
  }
}
