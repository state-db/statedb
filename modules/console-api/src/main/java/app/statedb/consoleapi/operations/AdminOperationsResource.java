package app.statedb.consoleapi.operations;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.SiteEntityLoader;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.entitlement.EffectiveEntitlement;
import app.statedb.persistence.entitlement.Entitlements;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.operations.ActiveLockView;
import app.statedb.persistence.operations.CompactionJobView;
import app.statedb.persistence.operations.OperationalInventoryFinder;
import app.statedb.persistence.stack.CompactionJobEnqueuer;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Site-admin visibility into live locks and the compaction work queue. */
@Path("/api/admin")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class AdminOperationsResource {

  @Inject OperationalInventoryFinder inventory;
  @Inject AuthorizedResource authorized;
  @Inject StackLockCanceller lockCanceller;
  @Inject CompactionJobEnqueuer compactionJobs;
  @Inject Entitlements entitlements;
  @Context UriInfo uriInfo;

  @GET
  @Path("/entitlement")
  @Authorize(action = "entitlement:Read", loader = SiteEntityLoader.class)
  public EntitlementDto entitlement() {
    EffectiveEntitlement effective = entitlements.effective();
    return new EntitlementDto(
        effective.tier().name().toLowerCase(Locale.ROOT),
        effective.status().wireValue(),
        Stack.count(),
        effective.stackLimit(),
        effective.expiresAt(),
        effective.validationFailure());
  }

  @GET
  @Path("/locks")
  @Authorize(action = "operations:Read", loader = SiteEntityLoader.class)
  public List<LockDto> locks() {
    return inventory.activeLocks(Instant.now()).stream().map(this::lockDto).toList();
  }

  @GET
  @Path("/compaction-jobs")
  @Authorize(action = "operations:Read", loader = SiteEntityLoader.class)
  public List<CompactionJobDto> compactionJobs() {
    return inventory.recentCompactionJobs().stream().map(this::compactionJobDto).toList();
  }

  @DELETE
  @Path("/stacks/{org}/{project}/{stack}/lock")
  @Authorize(action = "stack:Unlock", loader = StackEntityLoader.class)
  public Response forceUnlock() {
    String rawLockId = uriInfo.getQueryParameters().getFirst("lockId");
    if (rawLockId == null || rawLockId.isBlank()) {
      throw new BadRequestException("lockId is required");
    }
    UUID lockId;
    try {
      lockId = UUID.fromString(rawLockId);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("lockId must be a UUID");
    }

    StackRef ref = authorized.subject(StackRef.class);
    if (!lockCanceller.forceCancelLock(ref.stackId(), lockId)) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", "The lock changed or was already released."))
          .build();
    }
    return Response.noContent().build();
  }

  @POST
  @Path("/stacks/{org}/{project}/{stack}/compact")
  @Authorize(action = "compaction:Trigger", loader = StackEntityLoader.class)
  @Transactional
  public Response compact() {
    compactionJobs.enqueue(authorized.subject(StackRef.class).stackId());
    return Response.accepted().build();
  }

  private LockDto lockDto(ActiveLockView lock) {
    String holderKind =
        ServiceAccountCreator.SERVICE_ISS.equals(lock.holderIssuer()) ? "service" : "human";
    HolderDto holder =
        new HolderDto(
            holderKind,
            lock.holderId(),
            lock.holderDisplayName(),
            lock.holderEmail(),
            lock.lockedBy());
    UpdateDto update =
        new UpdateDto(
            lock.updateId(),
            lock.updateKind().wireValue(),
            lock.dryRun() ? null : lock.updateVersion(),
            lock.dryRun());
    return new LockDto(
        lock.lockId(),
        lock.stackId(),
        lock.org(),
        lock.project(),
        lock.stack(),
        holder,
        lock.lockedAt(),
        lock.heartbeatAt(),
        lock.expiresAt(),
        update);
  }

  private CompactionJobDto compactionJobDto(CompactionJobView job) {
    return new CompactionJobDto(
        job.id(),
        job.stackId(),
        job.org(),
        job.project(),
        job.stack(),
        job.status().name().toLowerCase(Locale.ROOT),
        job.attempts(),
        job.errorMessage(),
        job.createdAt(),
        job.startedAt(),
        job.completedAt());
  }

  public record HolderDto(
      String kind, UUID id, String displayName, String email, String fallback) {}

  public record UpdateDto(UUID id, String kind, Integer version, boolean preview) {}

  public record LockDto(
      UUID lockId,
      UUID stackId,
      String org,
      String project,
      String stack,
      HolderDto holder,
      Instant acquiredAt,
      Instant heartbeatAt,
      Instant expiresAt,
      UpdateDto update) {}

  public record CompactionJobDto(
      UUID id,
      UUID stackId,
      String org,
      String project,
      String stack,
      String status,
      int attempts,
      String errorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record EntitlementDto(
      String tier,
      String status,
      long stackCount,
      Long stackLimit,
      Instant expiresAt,
      String validationFailure) {}
}
