package app.statedb.pulumiapi.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.persistence.lock.LeaseContext;
import jakarta.ws.rs.WebApplicationException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaseContextHolderTest {

  @Test
  void requireReturnsContextEstablishedByFilter() {
    LeaseContext context =
        new LeaseContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    LeaseContextHolder holder = new LeaseContextHolder();

    holder.set(context);

    assertSame(context, holder.require());
  }

  @Test
  void requireWithoutFilterContextReturnsPulumiLeaseConflict() {
    LeaseContextHolder holder = new LeaseContextHolder();

    WebApplicationException failure = assertThrows(WebApplicationException.class, holder::require);

    assertEquals(409, failure.getResponse().getStatus());
    assertEquals(
        Map.of("code", 409, "message", "update lease is no longer valid"),
        failure.getResponse().getEntity());
  }
}
