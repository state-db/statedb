package app.statedb.app;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.vcs.VcsAppRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link TestDb#reset()} against schema drift: every base table in {@code public} outside
 * {@link #PRESERVED_TABLES} must be wiped by {@code TestDb.reset()} either directly or through a
 * {@code NOT NULL ... ON DELETE CASCADE} chain from {@link TestDb#RESET_ROOT_TABLES}.
 */
@QuarkusTest
class TestDbResetTest extends DatabaseTest {

  private static final Set<String> PRESERVED_TABLES =
      Set.of("flyway_schema_history", "service_secrets_keyset");

  @Inject VcsAppRegistry vcsAppRegistry;

  @Test
  void everyNonAllowlistedTableIsCoveredByResetCascade() {
    List<String> publicTables = publicBaseTables();
    Map<String, List<String>> cascadingChildren = mandatoryCascadingChildren();

    Set<String> covered = new HashSet<>(TestDb.RESET_ROOT_TABLES);
    ArrayDeque<String> queue = new ArrayDeque<>(TestDb.RESET_ROOT_TABLES);
    while (!queue.isEmpty()) {
      String parent = queue.poll();
      for (String child : cascadingChildren.getOrDefault(parent, List.of())) {
        if (covered.add(child)) {
          queue.add(child);
        }
      }
    }

    Set<String> uncovered = new TreeSet<>();
    for (String table : publicTables) {
      if (!PRESERVED_TABLES.contains(table) && !covered.contains(table)) {
        uncovered.add(table);
      }
    }

    assertTrue(
        uncovered.isEmpty(),
        "Tables not covered by TestDb.reset() (add a NOT NULL ON DELETE CASCADE FK from a reset"
            + " root, add the table to TestDb.RESET_ROOT_TABLES, or allowlist it in"
            + " TestDbResetTest.PRESERVED_TABLES): "
            + uncovered);
  }

  @Test
  void resetWipesAllNonAllowlistedTablesAndPreservesKeyset() {
    TestDb.SeededStackOwner seeded =
        db.seedOwnerWithStack("reset-guard-org", "reset-guard-tok", "proj", "dev");
    db.seedActiveUpdate(seeded.stack().stackId(), StackUpdate.Kind.UPDATE);
    requiringNew()
        .run(
            () -> {
              OidcProvider provider = new OidcProvider();
              provider.name = "reset-guard-provider";
              provider.issuerUrl = "https://reset-guard.invalid";
              provider.clientId = "cid";
              provider.clientSecret = "sec";
              provider.scopes = List.of("openid");
              provider.enabled = true;
              provider.persist();

              PolicyGrant grant = new PolicyGrant();
              grant.templateId = "StackWriteAccess";
              grant.orgId = seeded.orgId();
              grant.principalUserId = seeded.userId();
              grant.persist();
            });
    vcsAppRegistry.replace(
        new VcsAppRegistry.AppRegistration(
            "github.com",
            42L,
            "reset-guard-app",
            "reset-guard",
            "https://github.test/apps/reset-guard-app",
            "private-key",
            "client-id",
            "client-secret"));

    db.reset();

    Map<String, Long> remaining = new HashMap<>();
    for (String table : publicBaseTables()) {
      if (PRESERVED_TABLES.contains(table)) {
        continue;
      }
      long count = rowCount(table);
      if (count > 0) {
        remaining.put(table, count);
      }
    }

    assertTrue(
        remaining.isEmpty(),
        "Non-allowlisted tables still contained rows after TestDb.reset(): " + remaining);
    assertEquals(1L, rowCount("service_secrets_keyset"));
  }

  @SuppressWarnings("unchecked")
  private List<String> publicBaseTables() {
    return em.createNativeQuery(
            """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_type = 'BASE TABLE'
             ORDER BY table_name
            """)
        .getResultList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<String>> mandatoryCascadingChildren() {
    List<Object[]> edges =
        em.createNativeQuery(
                """
                SELECT parent.relname AS parent_table, child.relname AS child_table
                  FROM pg_constraint con
                  JOIN pg_class child ON child.oid = con.conrelid
                  JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
                  JOIN pg_class parent ON parent.oid = con.confrelid
                  JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
                 WHERE con.contype = 'f'
                   AND con.confdeltype = 'c'
                   AND child_ns.nspname = 'public'
                   AND parent_ns.nspname = 'public'
                   AND NOT EXISTS (
                         SELECT 1
                           FROM pg_attribute att
                          WHERE att.attrelid = con.conrelid
                            AND att.attnum = ANY (con.conkey)
                            AND NOT att.attnotnull
                       )
                """)
            .getResultList();
    Map<String, List<String>> children = new HashMap<>();
    for (Object[] edge : edges) {
      children
          .computeIfAbsent((String) edge[0], ignored -> new ArrayList<>())
          .add((String) edge[1]);
    }
    return children;
  }

  private long rowCount(String tableName) {
    assertTrue(
        tableName.matches("[a-z0-9_]+"), "unexpected table name in public schema: " + tableName);
    return ((Number)
            em.createNativeQuery("SELECT count(*) FROM \"" + tableName + "\"").getSingleResult())
        .longValue();
  }
}
