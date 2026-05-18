package io.statedb.pulumiapi.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.statedb.persistence.stack.StackAlreadyExistsException;
import io.statedb.persistence.stack.StackNotEmptyException;
import io.statedb.persistence.stack.StackNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the three stack-error envelopes. All three return {@code {message,
 * detail}} so the Pulumi CLI sees one consistent JSON shape regardless of which mapper fires.
 */
class StackExceptionMappersTest {

  private static final String ORG = "acme";
  private static final String PROJECT = "infra";
  private static final String STACK = "dev";
  private static final String QUALIFIED = ORG + "/" + PROJECT + "/" + STACK;

  @Test
  void notFoundMapperReturns404WithMessageAndDetail() {
    Response response =
        new StackNotFoundExceptionMapper()
            .toResponse(new StackNotFoundException(ORG, PROJECT, STACK));
    assertEnvelope(response, Response.Status.NOT_FOUND, "stack not found");
  }

  @Test
  void alreadyExistsMapperReturns409WithMessageAndDetail() {
    Response response =
        new StackAlreadyExistsExceptionMapper()
            .toResponse(new StackAlreadyExistsException(ORG, PROJECT, STACK));
    assertEnvelope(response, Response.Status.CONFLICT, "stack already exists");
  }

  @Test
  void notEmptyMapperReturns409WithMessageAndDetail() {
    Response response =
        new StackNotEmptyExceptionMapper()
            .toResponse(new StackNotEmptyException(ORG, PROJECT, STACK));
    assertEnvelope(response, Response.Status.CONFLICT, "stack is not empty");
  }

  private static void assertEnvelope(Response response, Response.Status status, String message) {
    assertEquals(status.getStatusCode(), response.getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
    assertEquals(Map.of("message", message, "detail", QUALIFIED), response.getEntity());
  }
}
