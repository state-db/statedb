package app.statedb.pulumiapi.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.entitlement.EntitlementDeniedException;
import app.statedb.persistence.entitlement.LicenseStatus;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntitlementDeniedExceptionMapperTest {
  @Test
  void returnsNonRetryablePulumiErrorWithoutLicenseClaims() {
    Response response =
        new EntitlementDeniedExceptionMapper()
            .toResponse(
                new EntitlementDeniedException("Stack creation", 10, 10, LicenseStatus.EXPIRED));
    assertEquals(403, response.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getEntity();
    assertEquals(10L, body.get("stackCount"));
    assertEquals(10L, body.get("stackLimit"));
    assertEquals("expired", body.get("licenseStatus"));
  }
}
