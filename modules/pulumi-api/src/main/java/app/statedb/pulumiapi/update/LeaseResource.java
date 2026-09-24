package app.statedb.pulumiapi.update;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.events.EventBatchAppender;
import app.statedb.persistence.journal.JournalAppender;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseContext;
import app.statedb.persistence.lock.LeaseLookup;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.lock.StackLockRenewer;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateCompleter;
import app.statedb.pulumiapi.auth.LeaseContextHolder;
import app.statedb.pulumiapi.update.events.EventBatchRequest;
import app.statedb.pulumiapi.update.journal.JournalPatchRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;

/** Update-token authenticated data plane for a held Pulumi update lease. */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class LeaseResource {

  @Inject StackLockCanceller lockCanceller;

  @Inject StackLockRenewer lockRenewer;

  @Inject LeaseLookup leaseLookup;

  @Inject LeaseContextHolder leaseHolder;

  @Inject JournalAppender journalAppender;

  @Inject EventBatchAppender eventBatchAppender;

  @Inject StackUpdateCompleter completer;

  @POST
  @Path("/update/{updateID}/renew_lease")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public RenewLeaseResponse renewLease(RenewLeaseRequest body) {
    // The body-token check goes through LeaseLookup so the comparison is constant-time on the
    // stored hash, not on plaintext UUIDs.
    LeaseContext ctx = leaseHolder.require();
    if (body != null && body.token != null && !body.token.isBlank()) {
      UUID bodyTok;
      try {
        bodyTok = UUID.fromString(body.token);
      } catch (IllegalArgumentException e) {
        throw new StackLockedException(ctx.stackId());
      }
      if (!leaseLookup.tokenMatches(ctx.leaseId(), bodyTok)) {
        throw new StackLockedException(ctx.stackId());
      }
    }

    Lease renewed = lockRenewer.renew(ctx.leaseId(), body != null ? body.duration : null);
    return new RenewLeaseResponse(
        renewed.plaintextToken().toString(), renewed.expiresAt().getEpochSecond());
  }

  @PATCH
  @Path("/update/{updateID}/journalentries")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response appendJournal(JournalPatchRequest body) {
    LeaseContext ctx = leaseHolder.require();

    // Vert.x's auto-decompression silently produces a null body for malformed gzip — without this
    // check that would look like a successful no-op PATCH and the CLI would move on with corrupt
    // state. Treat null/missing entries as 400.
    if (body == null || body.entries == null) {
      throw new RequestValidationException("request body missing 'entries' array");
    }
    if (body.entries.isEmpty()) {
      return Response.ok(Map.of()).build();
    }

    journalAppender.append(ctx.stackId(), ctx.updateId(), ctx.leaseId(), body.entries);
    return Response.ok(Map.of()).build();
  }

  @POST
  @Path("/update/{updateID}/events/batch")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response postEventsBatch(EventBatchRequest body) {
    LeaseContext ctx = leaseHolder.require();

    // Vert.x auto-decompresses gzip request bodies. A malformed gzip envelope yields a null body
    // — without this check that would look like a successful no-op and the CLI would move on
    // with corrupt event state. Treat null/missing events as 400.
    if (body == null || body.events == null) {
      throw new RequestValidationException("request body missing 'events' array");
    }
    if (body.events.isEmpty()) {
      // Pulumi Cloud returns 400 "No events provided" for empty batches — match that so a CLI
      // that accidentally sends an empty batch sees the same wire behavior.
      throw new RequestValidationException("no events provided");
    }

    eventBatchAppender.append(ctx.stackId(), ctx.updateId(), ctx.leaseId(), body.events);
    return Response.ok(Map.of()).build();
  }

  // Wave-1 fallback: the OpenAPI defines these but the CLI only hits them when journalVersion
  // negotiation fails. We negotiate journaling on /update (acquire), so a request here means a
  // protocol-version mismatch — surface that explicitly rather than silently 404-ing.
  @PATCH
  @Path("/update/{updateID}/checkpoint")
  @Produces(MediaType.APPLICATION_JSON)
  public Response checkpointNotImplemented() {
    return notImplementedJournaling();
  }

  @PATCH
  @Path("/update/{updateID}/checkpointdelta")
  @Produces(MediaType.APPLICATION_JSON)
  public Response checkpointDeltaNotImplemented() {
    return notImplementedJournaling();
  }

  @PATCH
  @Path("/update/{updateID}/checkpointverbatim")
  @Produces(MediaType.APPLICATION_JSON)
  public Response checkpointVerbatimNotImplemented() {
    return notImplementedJournaling();
  }

  @POST
  @Path("/update/{updateID}/complete")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response completeUpdate(CompleteUpdateRequest body) {
    LeaseContext ctx = leaseHolder.require();

    if (body == null || body.status == null || body.status.isBlank()) {
      throw new RequestValidationException("request body missing 'status'");
    }
    StackUpdate.Status terminal =
        switch (body.status) {
          case "succeeded" -> StackUpdate.Status.SUCCEEDED;
          case "failed" -> StackUpdate.Status.FAILED;
          default ->
              throw new RequestValidationException(
                  "status must be 'succeeded' or 'failed', got '" + body.status + "'");
        };

    completer.complete(ctx.leaseId(), ctx.stackId(), ctx.updateId(), terminal);
    return Response.ok(Map.of()).build();
  }

  @DELETE
  @Path("/update/{updateID}")
  public Response cancelUpdate() {
    LeaseContext ctx = leaseHolder.require();
    lockCanceller.gracefulCancel(ctx.leaseId(), ctx.stackId(), ctx.updateId());
    return Response.noContent().build();
  }

  private static Response notImplementedJournaling() {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            Map.of(
                "code",
                501,
                "message",
                "checkpoint endpoints are not implemented; this server negotiates "
                    + "journalVersion: 1 — use PATCH .../journalentries"))
        .build();
  }
}
