package io.statedb.persistence.operations;

import io.statedb.persistence.update.StackUpdate;
import java.time.Instant;
import java.util.UUID;

/** Operator-facing projection of one currently live stack lock. */
public record ActiveLockView(
    UUID lockId,
    UUID stackId,
    String org,
    String project,
    String stack,
    String lockedBy,
    UUID holderId,
    String holderIssuer,
    String holderDisplayName,
    String holderEmail,
    Instant lockedAt,
    Instant heartbeatAt,
    Instant expiresAt,
    UUID updateId,
    StackUpdate.Kind updateKind,
    int updateVersion,
    boolean dryRun) {}
