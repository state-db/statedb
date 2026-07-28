package io.statedb.persistence.stack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.panache.common.Sort;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Iterator;
import java.util.UUID;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * Reconstructs the current Pulumi DeploymentV3 from the compacted base snapshot and journal rows.
 *
 * <p>This deliberately uses a scalar native projection instead of hydrating {@code JournalEntry}
 * entities: an export touches every row after the compaction boundary, and replay only needs the
 * primitive discriminator/index columns plus the JSONB payload.
 */
@ApplicationScoped
public class StackAssembler {

  private static final Logger LOG = Logger.getLogger(StackAssembler.class);

  @Inject EntityManager em;

  @Inject ObjectMapper mapper;

  @Transactional
  public AssembledDeployment assemble(UUID stackId) {
    return replay(stackId, loadBaseSnapshotOrEmpty(stackId), null);
  }

  /**
   * Reconstructs the deployment as of a historical applied version — a completed update's {@code
   * end_journal_seq}. The base is the newest snapshot at or below the boundary, found by position
   * rather than through {@code stack_state.base_snapshot_id} (which may already be past the
   * boundary). Position alone is not enough: import and rename install snapshots without advancing
   * the journal, so a later version's snapshot can share the target's boundary, and its content is
   * not the fold of the journal prefix. Those operations stamp {@code
   * stack_updates.base_snapshot_version}; the earliest stamp after {@code version} fences the
   * lookup — only snapshots below it are candidates, and ties at one boundary resolve to the
   * highest surviving snapshot version. With no candidate at or below the boundary, replay starts
   * from the empty base, the same path a stack takes before its first compaction.
   */
  @Transactional
  public AssembledDeployment assembleAt(UUID stackId, long endJournalSeq, int version) {
    return replay(
        stackId, loadNearestSnapshotOrEmpty(stackId, endJournalSeq, version), endJournalSeq);
  }

  private AssembledDeployment replay(UUID stackId, BaseSnapshot baseSnapshot, Long endSeq) {
    ObjectNode currentBase = baseSnapshot.checkpoint();
    long baseSeq = baseSnapshot.compactedFromJournalSeq();
    UUID priorUpdateId = null;
    JournalReplayer replayer = new JournalReplayer(mapper, currentBase);

    try (Stream<EntryRow> entries = streamEntries(stackId, baseSeq, endSeq)) {
      Iterator<EntryRow> iterator = entries.iterator();
      EntryRow pending = iterator.hasNext() ? iterator.next() : null;
      while (pending != null) {
        EntryRow entry = pending;
        pending = iterator.hasNext() ? iterator.next() : null;

        if (priorUpdateId != null && !entry.updateId().equals(priorUpdateId)) {
          currentBase = (ObjectNode) replayer.generateDeployment().deployment().deepCopy();
          replayer = new JournalReplayer(mapper, currentBase);
        }

        boolean hadNewResourcesBeforeRebuild = entry.kind() == 7 && replayer.hasNewResources();
        replayer.add(entry);
        if (hadNewResourcesBeforeRebuild
            && pending != null
            && pending.updateId().equals(entry.updateId())) {
          LOG.warnf(
              "stack=%s update=%s seq=%d: RebuiltBaseState was not the last entry with new resources",
              stackId, entry.updateId(), entry.sequenceId());
        }
        priorUpdateId = entry.updateId();
      }
    }

    return replayer.generateDeployment();
  }

  private BaseSnapshot loadBaseSnapshotOrEmpty(UUID stackId) {
    UUID baseSnapshotId =
        (UUID)
            em.createNativeQuery("SELECT base_snapshot_id FROM stack_state WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    if (baseSnapshotId != null) {
      Snapshot snapshot = em.find(Snapshot.class, baseSnapshotId);
      if (snapshot == null) {
        throw new IllegalStateException("base snapshot not found: " + baseSnapshotId);
      }
      if (!snapshot.checkpoint.isObject()) {
        throw new IllegalStateException("base snapshot checkpoint must be a JSON object");
      }
      return new BaseSnapshot(
          (ObjectNode) snapshot.checkpoint.deepCopy(), snapshot.compactedFromJournalSeq);
    }
    return emptyBase();
  }

  private BaseSnapshot loadNearestSnapshotOrEmpty(UUID stackId, long endJournalSeq, int version) {
    StackUpdate nextLineageBreak =
        StackUpdate.<StackUpdate>find(
                "stackId = ?1 and version > ?2 and baseSnapshotVersion is not null",
                Sort.ascending("version"),
                stackId,
                version)
            .firstResult();
    Snapshot snapshot =
        nextLineageBreak == null
            ? Snapshot.<Snapshot>find(
                    "stackId = ?1 and compactedFromJournalSeq <= ?2",
                    Sort.descending("compactedFromJournalSeq", "version"),
                    stackId,
                    endJournalSeq)
                .firstResult()
            : Snapshot.<Snapshot>find(
                    "stackId = ?1 and compactedFromJournalSeq <= ?2 and version < ?3",
                    Sort.descending("compactedFromJournalSeq", "version"),
                    stackId,
                    endJournalSeq,
                    nextLineageBreak.baseSnapshotVersion)
                .firstResult();
    if (snapshot == null) {
      return emptyBase();
    }
    if (!snapshot.checkpoint.isObject()) {
      throw new IllegalStateException("snapshot checkpoint must be a JSON object");
    }
    return new BaseSnapshot(
        (ObjectNode) snapshot.checkpoint.deepCopy(), snapshot.compactedFromJournalSeq);
  }

  private BaseSnapshot emptyBase() {
    ObjectNode deployment = mapper.createObjectNode();
    deployment.set("resources", mapper.createArrayNode());
    return new BaseSnapshot(deployment, 0L);
  }

  private Stream<EntryRow> streamEntries(UUID stackId, long baseSeq, Long endSeqInclusive) {
    String sql =
        """
        SELECT stack_id, update_id, sequence_id, kind, operation_id, is_refresh,
               remove_old, remove_new, delete_old, delete_new,
               pending_replacement_old, pending_replacement_new,
               CAST(payload AS text)
          FROM journal_entries
         WHERE stack_id = ?1 AND sequence_id > ?2
        """
            + (endSeqInclusive != null ? "   AND sequence_id <= ?3\n" : "")
            + " ORDER BY sequence_id ASC";
    var query =
        em.createNativeQuery(sql)
            .setParameter(1, stackId)
            .setParameter(2, baseSeq)
            .setHint("org.hibernate.fetchSize", 512)
            .setHint("org.hibernate.readOnly", true);
    if (endSeqInclusive != null) {
      query.setParameter(3, endSeqInclusive);
    }
    @SuppressWarnings("unchecked")
    Stream<Object[]> rows = query.getResultStream();
    return rows.map(this::entryRow);
  }

  private EntryRow entryRow(Object[] row) {
    try {
      return new EntryRow(
          (UUID) row[0],
          (UUID) row[1],
          ((Number) row[2]).longValue(),
          ((Number) row[3]).shortValue(),
          longOrNull(row[4]),
          (Boolean) row[5],
          longOrNull(row[6]),
          longOrNull(row[7]),
          longOrNull(row[8]),
          longOrNull(row[9]),
          longOrNull(row[10]),
          longOrNull(row[11]),
          mapper.readTree((String) row[12]));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("journal payload is not valid JSON", e);
    }
  }

  private static Long longOrNull(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  record EntryRow(
      UUID stackId,
      UUID updateId,
      long sequenceId,
      short kind,
      Long operationId,
      boolean isRefresh,
      Long removeOld,
      Long removeNew,
      Long deleteOld,
      Long deleteNew,
      Long pendingReplacementOld,
      Long pendingReplacementNew,
      JsonNode payload) {}

  private record BaseSnapshot(ObjectNode checkpoint, long compactedFromJournalSeq) {}
}
