package io.statedb.pulumiapi.stack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackHistoryFinder;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * Serves {@code GET /api/stacks/{org}/{project}/{stack}/updates} — the endpoint behind {@code
 * pulumi stack history}. With {@code output-type} unset the CLI expects the legacy shape {@code
 * {"updates":[...], "itemsPerPage": N}} (it sends {@code pageSize=10&page=1}); a non-positive page
 * returns every row.
 */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}/updates")
public class StackHistoryResource {

  // Bounds the per-page row count so an oversized pageSize can't ask the DB for an unbounded result
  // set. The CLI sends pageSize=10; larger requests are clamped rather than rejected.
  private static final int MAX_PAGE_SIZE = 100;

  @Inject StackHistoryFinder finder;

  @Inject AuthorizedResource authorized;

  @GET
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public HistoryDto history(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("pageSize") @DefaultValue("100") int pageSize) {
    // page <= 0 is the legacy "all rows" sentinel, so only pageSize needs guarding: a non-positive
    // limit is a malformed request, and an oversized one is clamped before it reaches JPA.
    if (pageSize < 1) {
      throw new RequestValidationException("pageSize must be positive");
    }
    int boundedPageSize = Math.min(pageSize, MAX_PAGE_SIZE);
    StackRef ref = authorized.subject(StackRef.class);
    List<UpdateInfo> updates =
        finder.find(ref.stackId(), page, boundedPageSize).stream()
            .map(StackHistoryResource::toInfo)
            .toList();
    return new HistoryDto(updates, boundedPageSize);
  }

  private static UpdateInfo toInfo(StackUpdate u) {
    return new UpdateInfo(
        u.kind.wireValue(),
        u.startedAt.getEpochSecond(),
        u.message,
        environmentOf(u),
        u.config == null ? Map.of() : u.config,
        result(u.status),
        u.endedAt == null ? 0 : u.endedAt.getEpochSecond(),
        u.version);
  }

  // Pulumi nests the run environment under UpdateMetadata.environment (map[string]string); we store
  // the metadata map verbatim, so lift that sub-map out for UpdateInfo.environment.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> environmentOf(StackUpdate u) {
    if (u.metadata == null) {
      return Map.of();
    }
    return u.metadata.get("environment") instanceof Map<?, ?> env
        ? (Map<String, Object>) env
        : Map.of();
  }

  // Pulumi's UpdateResult: an unfinished update is "in-progress", a clean finish "succeeded", and
  // both failed and cancelled updates collapse to "failed" (the CLI has no separate cancelled
  // state).
  private static String result(StackUpdate.Status status) {
    return switch (status) {
      case IN_PROGRESS -> "in-progress";
      case SUCCEEDED -> "succeeded";
      case FAILED, CANCELLED -> "failed";
    };
  }

  // Mirrors apitype.UpdateInfo. config and environment are carried through verbatim from what the
  // update was started with (both share Pulumi's wire shapes); an absent map serializes as {}
  // rather
  // than being dropped. resourceChanges is omitted for now. Times are epoch seconds.
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record UpdateInfo(
      @JsonProperty("kind") String kind,
      @JsonProperty("startTime") long startTime,
      @JsonProperty("message") String message,
      @JsonProperty("environment") Map<String, Object> environment,
      @JsonProperty("config") Map<String, Object> config,
      @JsonProperty("result") String result,
      @JsonProperty("endTime") long endTime,
      @JsonProperty("version") int version) {}

  // Legacy (non-paginated output-type) shape: apitype.GetHistoryResponse.
  public record HistoryDto(
      @JsonProperty("updates") List<UpdateInfo> updates,
      @JsonProperty("itemsPerPage") int itemsPerPage) {}
}
