package app.statedb.consoleapi.workspace;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.UpdateEntityLoader;
import app.statedb.persistence.events.UpdateEventReader;
import app.statedb.persistence.report.ChangeReport;
import app.statedb.persistence.report.ChangeReportDeriver;
import app.statedb.persistence.report.MarkdownReportRenderer;
import app.statedb.persistence.report.ReportLimits;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

/**
 * What one run changed, as a pull-request-shaped report.
 *
 * <p>Two representations of one derivation: {@code format=json} serves the {@link ChangeReport}
 * itself, {@code format=markdown} serves it rendered. The JSON form is what the design means by "no
 * AI in the application" — anything that wants to summarise a run can read the same sanitized model
 * this renderer reads, outside our process, rather than us growing a summariser.
 *
 * <p>Addressed by update id alone. A run is globally identified by its id, and the stack it belongs
 * to is a fact about it rather than part of its address; {@link UpdateEntityLoader} resolves the
 * stack and applies the stack's own read-visibility rule.
 *
 * <p><b>Events come from {@link UpdateEventReader}, never from a query.</b> {@code
 * EventArchiveFolder} folds a terminal run's events and leaves its summary rows behind, so a direct
 * read of {@code update_events} would quietly return a summary-only report for every run past the
 * fold grace period — the report would be wrong rather than absent, which is the failure shape
 * worth designing against. The reader answers from rows or archive under one snapshot either way.
 */
@Path("/api/updates/{updateId}")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class UpdateReportResource {

  /**
   * The bound on how much of a run's event stream one report reads.
   *
   * <p>Every returned event materializes its payload as a tree, so this event-count safety valve is
   * not cosmetic. It is not a hard heap bound: one event can be large, and JDBC materializes an
   * archived run's compressed blob before the reader incrementally decodes it. A run beyond this
   * count degrades to a report explicitly marked truncated.
   */
  private static final int MAX_EVENTS = 50_000;

  @Inject AuthorizedResource authorized;
  @Inject UpdateEventReader eventReader;
  @Inject ChangeReportDeriver deriver;
  @Inject MarkdownReportRenderer renderer;
  @Inject UriInfo uriInfo;

  @GET
  @Path("/report")
  @Authorize(action = "stack:Read", loader = UpdateEntityLoader.class)
  public Response report(
      @PathParam("updateId") String updateId, @QueryParam("format") String requestedFormat) {
    // Parsed before any work, so an unusable format is a 400 rather than a wasted read.
    boolean json = Format.parse(requestedFormat) == Format.JSON;
    StackRef ref = authorized.subject(StackRef.class);
    UUID id = UUID.fromString(updateId);

    // The loader proved this update exists and belongs to a stack the principal may read; this
    // read is for the row itself, which derivation needs.
    StackUpdate update =
        StackUpdate.<StackUpdate>find("id = ?1 and stackId = ?2", id, ref.stackId())
            .firstResultOptional()
            .orElseThrow(NotFoundException::new);

    ReportLimits limits = ReportLimits.defaults();
    UpdateEventReader.EventStream stream = eventReader.stream(ref.stackId(), id, MAX_EVENTS);
    ChangeReport report =
        deriver.derive(ref, update, stream.events(), limits).withTruncated(stream.truncated());

    Object payload = json ? report : renderer.render(report, consoleLink(ref, update), limits);
    return Response.ok(payload, MediaType.APPLICATION_JSON_TYPE).build();
  }

  /**
   * The run's console permalink, built from this request's own base URI.
   *
   * <p>Deriving it from the request rather than from configuration is right for a read endpoint:
   * the caller reached us at some address, and that address is by construction one that works for
   * them. A background publisher has no request to derive it from and will need a configured base
   * URL instead — that belongs with the publisher, which is the thing that knows a comment outlives
   * the request that caused it.
   *
   * <p>Targets the canonical {@code /console/...} route rather than the bare {@code /org/...} form,
   * which only redirects to it. A permalink that lands on a pull request should not spend a
   * redirect to get where it is going.
   *
   * <p>A dry run has no applied version to address — it does not consume the sequence — so it is
   * addressed by update id, which is how {@code router.tsx} distinguishes the two routes.
   */
  private URI consoleLink(StackRef ref, StackUpdate update) {
    String path =
        String.join(
            "/",
            "",
            "console",
            ref.orgName(),
            ref.projectName(),
            ref.stackName(),
            update.dryRun ? "previews" : "updates",
            update.dryRun ? update.id.toString() : Integer.toString(update.version));
    try {
      // Building the URI with a null scheme and authority leaves `path` as a path component, so
      // resolving percent-encodes it: a stack name cannot walk out of its segment or add a query.
      return uriInfo.getBaseUri().resolve(new URI(null, null, path, null, null));
    } catch (URISyntaxException unbuildable) {
      // A name that cannot be expressed in a URI at all is not worth failing the report over. The
      // report is still correct; it just arrives without a link, and the renderer omits it.
      return null;
    }
  }

  private enum Format {
    MARKDOWN,
    JSON;

    /** Defaults to markdown, which is what the surface this feature exists for consumes. */
    static Format parse(String requested) {
      if (requested == null || requested.isBlank() || "markdown".equals(requested)) {
        return MARKDOWN;
      }
      if ("json".equals(requested)) {
        return JSON;
      }
      throw new BadRequestException("format must be one of markdown, json");
    }
  }
}
