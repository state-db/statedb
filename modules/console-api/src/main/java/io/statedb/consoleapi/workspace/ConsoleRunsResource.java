package io.statedb.consoleapi.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.events.UpdateEvent;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.ConsoleUpdateHistoryFinder;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only run detail and event-log endpoints used by console permalinks. */
@Path("/api/pulumi/stacks/{org}/{project}/{stack}")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class ConsoleRunsResource {

  private static final int EVENT_PAGE_SIZE = 500;
  private static final Duration EVENT_RETENTION = Duration.ofDays(30);

  @Inject AuthorizedResource authorized;
  @Inject ConsoleUpdateHistoryFinder finder;

  @GET
  @Path("/updates/{version}")
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  public RunResponse update(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("version") int version) {
    StackRef ref = authorized.subject(StackRef.class);
    return response(finder.findUpdate(ref.stackId(), version).orElseThrow(NotFoundException::new));
  }

  @GET
  @Path("/previews/{updateId}")
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  public RunResponse preview(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateId") String updateId) {
    StackRef ref = authorized.subject(StackRef.class);
    return response(
        finder.findPreview(ref.stackId(), parseUuid(updateId)).orElseThrow(NotFoundException::new));
  }

  @GET
  @Path("/updates/{updateId}/events")
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  public EventsResponse events(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateId") String updateId,
      @QueryParam("after") Integer requestedAfter) {
    StackRef ref = authorized.subject(StackRef.class);
    UUID id = parseUuid(updateId);
    StackUpdate update =
        StackUpdate.<StackUpdate>find("id = ?1 and stackId = ?2", id, ref.stackId())
            .firstResultOptional()
            .orElseThrow(NotFoundException::new);
    int after = requestedAfter == null ? -1 : requestedAfter;
    if (after < -1) throw new BadRequestException("after must be at least -1");
    List<UpdateEvent> events = finder.events(ref.stackId(), id, after, EVENT_PAGE_SIZE);
    List<EventResponse> rows =
        events.stream()
            .map(
                event ->
                    new EventResponse(event.sequence, event.kind, event.severity, event.payload))
            .toList();
    Integer nextAfter = rows.size() == EVENT_PAGE_SIZE ? rows.getLast().sequence() : null;
    boolean expired =
        rows.isEmpty()
            && after == -1
            && update.startedAt.isBefore(Instant.now().minus(EVENT_RETENTION));
    return new EventsResponse(rows, nextAfter, expired);
  }

  private RunResponse response(StackUpdate update) {
    JsonNode summary =
        UpdateEvent.<UpdateEvent>find("updateId = ?1 and kind = ?2", update.id, "summary")
            .firstResultOptional()
            .map(event -> event.payload.path("summaryEvent"))
            .orElse(null);
    return new RunResponse(
        update.id,
        update.dryRun ? null : update.version,
        update.kind.wireValue(),
        update.status.wireValue(),
        update.message != null ? update.message : update.description,
        update.runtime,
        update.pulumiVersion,
        update.startedAt,
        update.endedAt,
        summary,
        update.config == null ? Map.of() : update.config,
        update.options == null ? Map.of() : update.options,
        update.metadata == null ? Map.of() : update.metadata);
  }

  private static UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      throw new NotFoundException();
    }
  }

  public record RunResponse(
      UUID id,
      Integer version,
      String kind,
      String status,
      String message,
      String runtime,
      String pulumiVersion,
      Instant startedAt,
      Instant endedAt,
      JsonNode summary,
      Map<String, Object> config,
      Map<String, Object> options,
      Map<String, Object> metadata) {}

  public record EventResponse(int sequence, String kind, String severity, JsonNode payload) {}

  public record EventsResponse(List<EventResponse> events, Integer nextAfter, boolean expired) {}
}
