package app.statedb.pulumiapi.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.eula.EulaNotAcceptedException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EulaNotAcceptedExceptionMapperTest {

  @Test
  void rendersAPulumiErrorResponseTellingTheOperatorWhereToAccept() {
    Response response =
        new EulaNotAcceptedExceptionMapper()
            .toResponse(new EulaNotAcceptedException("Stack creation", "1.0"));

    assertEquals(403, response.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getEntity();
    assertEquals(403, body.get("code"));
    assertEquals("1.0", body.get("eulaVersion"));
    String message = (String) body.get("message");
    assertTrue(message.contains("site administrator"), message);
    assertTrue(message.contains("web console"), message);
  }
}
