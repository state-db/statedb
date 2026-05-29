package io.statedb.persistence.stack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    ObjectNode currentBase = loadBaseSnapshotOrEmpty(stackId);
    long baseSeq = 0L;
    UUID priorUpdateId = null;
    JournalReplayer replayer = new JournalReplayer(mapper, currentBase);

    try (Stream<EntryRow> entries = streamEntries(stackId, baseSeq)) {
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

  private ObjectNode loadBaseSnapshotOrEmpty(UUID stackId) {
    // Only base_snapshot_id is read today; base_snapshot_version (and the
    // baseSeq filter in streamEntries) gets wired up when snapshot storage
    // lands. Selecting it here would make the unwired state invisible.
    UUID baseSnapshotId =
        (UUID)
            em.createNativeQuery("SELECT base_snapshot_id FROM stack_state WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    if (baseSnapshotId != null) {
      throw new IllegalStateException(
          "base snapshot loading is not available until snapshot storage lands");
    }
    ObjectNode deployment = mapper.createObjectNode();
    deployment.set("resources", mapper.createArrayNode());
    return deployment;
  }

  private Stream<EntryRow> streamEntries(UUID stackId, long baseSeq) {
    @SuppressWarnings("unchecked")
    Stream<Object[]> rows =
        em.createNativeQuery(
                """
                SELECT stack_id, update_id, sequence_id, kind, operation_id, is_refresh,
                       remove_old, remove_new, delete_old, delete_new,
                       pending_replacement_old, pending_replacement_new,
                       CAST(payload AS text)
                  FROM journal_entries
                 WHERE stack_id = ?1 AND sequence_id > ?2
                 ORDER BY sequence_id ASC
                """)
            .setParameter(1, stackId)
            .setParameter(2, baseSeq)
            .setHint("org.hibernate.fetchSize", 512)
            .setHint("org.hibernate.readOnly", true)
            .getResultStream();
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
}
