package io.statedb.consoleapi.operations;

import io.quarkus.security.identity.SecurityIdentity;
import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.identity.AdminAllowList;
import io.statedb.persistence.identity.ServiceAccountCreator;
import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.lock.StackLockCanceller;
import io.statedb.persistence.operations.ActiveLockView;
import io.statedb.persistence.operations.CompactionJobView;
import io.statedb.persistence.operations.OperationalInventoryFinder;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Site-admin visibility into live locks and the compaction work queue. */
@Path("/api/admin")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class AdminOperationsResource {

  @Inject SecurityIdentity identity;
  @Inject AdminAllowList adminAllowList;
  @Inject OperationalInventoryFinder inventory;
  @Inject AuthorizedResource authorized;
  @Inject StackLockCanceller lockCanceller;
  @Inject CompactionJobEnqueuer compactionJobs;
  @Context UriInfo uriInfo;

  @GET
  @Path("/locks")
  public List<LockDto> locks() {
    requireAdmin();
    return inventory.activeLocks(Instant.now()).stream().map(this::lockDto).toList();
  }

  @GET
  @Path("/compaction-jobs")
  public List<CompactionJobDto> compactionJobs() {
    requireAdmin();
    return inventory.recentCompactionJobs().stream().map(this::compactionJobDto).toList();
  }

  @DELETE
  @Path("/stacks/{org}/{project}/{stack}/lock")
  @Authorize(action = "stack:Unlock", loader = StackEntityLoader.class)
  @Transactional
  public Response forceUnlock(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
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
    StackLock lock =
        StackLock.<StackLock>find("id = ?1 and stackId = ?2", lockId, ref.stackId())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    if (lock == null) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", "The lock changed or was already released."))
          .build();
    }
    lockCanceller.cancel(lock.id, ref.stackId(), lock.updateId);
    return Response.noContent().build();
  }

  @POST
  @Path("/stacks/{org}/{project}/{stack}/compact")
  @Authorize(action = "compaction:Trigger", loader = StackEntityLoader.class)
  @Transactional
  public Response compact(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
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

  private void requireAdmin() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)
        || !adminAllowList.isAdmin(jwt.getIssuer(), jwt.getSubject())) {
      throw new ForbiddenException();
    }
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
}
