package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackAssemblerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "assembler-org";

  @Inject StackAssembler assembler;
  @Inject StackCreator stackCreator;
  @Inject EntityManager em;

  private UUID stackId;
  private int updateVersion;
  private long sequence;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Assembler Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  @Test
  void emptyStackReturnsEmptyDeployment() {
    AssembledDeployment assembled = assembler.assemble(stackId);

    assertEquals(3, assembled.version());
    assertTrue(assembled.features().isEmpty());
    assertEquals(0, assembled.deployment().required("resources").size());
    assertEquals(
        "v3.242.0", assembled.deployment().required("manifest").required("version").asText());
  }

  @Test
  void successAppendsState() throws Exception {
    UUID update = update();
    entry(update, 0, 1L, "{\"operation\":{\"resource\":" + res("a") + ",\"type\":\"creating\"}}");
    entry(update, 1, 1L, "{\"state\":" + res("a") + "}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
  }

  @Test
  void successRemoveOldDropsBaseAtIndex() throws Exception {
    UUID update = update();
    write(update, "[" + res("a") + "," + res("b") + "]");
    entry(update, 1, 1L, "remove_old", 1L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
  }

  @Test
  void successRemoveNewDropsNewByOpId() throws Exception {
    UUID update = update();
    success(update, 1L, res("a"));
    success(update, 2L, res("b"));
    entry(update, 1, 3L, "remove_new", 2L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
  }

  @Test
  void successDeleteAndPendingReplacementMutateResources() throws Exception {
    UUID update = update();
    write(update, "[" + res("base-delete") + "," + res("base-pending") + "]");
    success(update, 1L, res("new-delete"));
    success(update, 2L, res("new-pending"));
    entry(update, 1, 3L, "delete_old", 0L, "{}");
    entry(update, 1, 4L, "pending_replacement_old", 1L, "{}");
    entry(update, 1, 5L, "delete_new", 1L, "{}");
    entry(update, 1, 6L, "pending_replacement_new", 2L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertTrue(find(resources, "new-delete").required("delete").asBoolean());
    assertTrue(find(resources, "new-pending").required("pendingReplacement").asBoolean());
    assertTrue(find(resources, "base-delete").required("delete").asBoolean());
    assertTrue(find(resources, "base-pending").required("pendingReplacement").asBoolean());
  }

  @Test
  void failureClearsOnlyMatchingPendingOp() throws Exception {
    UUID update = update();
    entry(update, 0, 1L, "{\"operation\":{\"resource\":" + res("a") + ",\"type\":\"creating\"}}");
    entry(update, 0, 2L, "{\"operation\":{\"resource\":" + res("b") + ",\"type\":\"creating\"}}");
    entry(update, 2, 1L, "{}");

    JsonNode pending = assembler.assemble(stackId).deployment().required("pending_operations");

    assertEquals(1, pending.size());
    assertEquals(urn("b"), pending.get(0).required("resource").required("urn").asText());
  }

  @Test
  void refreshSuccessReplacesRemovesAndRebuildsDependencies() throws Exception {
    UUID update = update();
    write(
        update,
        "["
            + res("parent")
            + ","
            + resource(
                "child",
                "\"parent\":\"" + urn("parent") + "\",\"dependencies\":[\"" + urn("parent") + "\"]")
            + "]");
    entry(update, 3, 1L, "remove_old", 0L, "{}");

    JsonNode child = find(assembler.assemble(stackId).deployment().required("resources"), "child");

    assertFalse(child.required("dependencies").elements().hasNext());
    assertEquals("", child.required("parent").asText());
  }

  @Test
  void refreshSuccessClearsDanglingReplaceWith() throws Exception {
    UUID update = update();
    write(
        update,
        "["
            + res("target")
            + ","
            + resource("dependent", "\"replaceWith\":[\"" + urn("target") + "\"]")
            + "]");
    entry(update, 3, 1L, "remove_old", 0L, "{}");

    JsonNode dependent =
        find(assembler.assemble(stackId).deployment().required("resources"), "dependent");

    assertEquals("", dependent.required("replaceWith").get(0).asText());
  }

  @Test
  void outputsReplacesNewByOpId() throws Exception {
    UUID update = update();
    success(update, 1L, resource("stack", "\"type\":\"pulumi:pulumi:Stack\""));
    entry(
        update,
        4,
        1L,
        "remove_new",
        1L,
        "{\"state\":"
            + resource(
                "stack", "\"type\":\"pulumi:pulumi:Stack\",\"outputs\":{\"petName\":\"spot\"}")
            + "}");

    JsonNode stack = find(assembler.assemble(stackId).deployment().required("resources"), "stack");

    assertEquals("spot", stack.required("outputs").required("petName").asText());
  }

  @Test
  void secretsManagerSetsProviderAndElidesNoOp() throws Exception {
    UUID update = update();
    entry(update, 6, 0L, "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"u\"}}}");
    // Second entry has matching type+state but adds an extraneous field. The
    // elide branch (sameSecretsProvider) compares only type+state, so a working
    // elision keeps the first provider node verbatim and the extra field never
    // makes it into the assembled output. Without elision, the second entry
    // wholesale-replaces secrets_providers and `extra` would leak through.
    entry(
        update,
        6,
        0L,
        "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"u\"},\"extra\":\"x\"}}");

    JsonNode provider = assembler.assemble(stackId).deployment().required("secrets_providers");

    assertEquals("service", provider.required("type").asText());
    assertFalse(provider.has("extra"));
  }

  @Test
  void rebuiltBaseStatePromotesNewToBase() throws Exception {
    UUID update = update();
    success(update, 1L, res("a"));
    entry(update, 7, 0L, "{}");
    success(update, 2L, res("b"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(2, resources.size());
    assertEquals(urn("b"), resources.get(0).required("urn").asText());
    assertEquals(urn("a"), resources.get(1).required("urn").asText());
  }

  @Test
  void crossUpdateOpIdReuseIsolated() throws Exception {
    UUID first = update();
    success(first, 1L, res("first"));
    UUID second = update();
    success(second, 1L, res("second"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(2, resources.size());
    assertEquals(urn("second"), resources.get(0).required("urn").asText());
    assertEquals(urn("first"), resources.get(1).required("urn").asText());
  }

  @Test
  void normalizeUrnReferencesFollowsAliasesAndSetsFeatures() throws Exception {
    UUID update = update();
    success(
        update,
        1L,
        resource("a", "\"aliases\":[\"" + urn("old-a") + "\"],\"viewOf\":\"" + urn("view") + "\""));
    success(update, 2L, resource("c", "\"dependencies\":[\"" + urn("old-a") + "\"]"));

    AssembledDeployment assembled = assembler.assemble(stackId);
    JsonNode resources = assembled.deployment().required("resources");

    assertEquals(4, assembled.version());
    assertEquals("views", assembled.features().get(0));
    assertEquals(urn("a"), find(resources, "c").required("dependencies").get(0).asText());
    assertFalse(find(resources, "a").has("aliases"));
  }

  @Test
  void unknownKindThrowsWithContext() throws Exception {
    UUID update = update();
    entry(update, 99, 0L, "{}");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(stackId));

    assertTrue(e.getMessage().contains("stack=" + stackId));
    assertTrue(e.getMessage().contains("update=" + update));
    assertTrue(e.getMessage().contains("kind=99"));
  }

  private UUID update() {
    UUID updateId = UUID.randomUUID();
    int version = ++updateVersion;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "INSERT INTO stack_updates "
                            + "(id, stack_id, version, kind, status, start_journal_seq) "
                            + "VALUES (?1, ?2, ?3, 'update', 'succeeded', ?4)")
                    .setParameter(1, updateId)
                    .setParameter(2, stackId)
                    .setParameter(3, version)
                    .setParameter(4, sequence)
                    .executeUpdate());
    return updateId;
  }

  private void success(UUID updateId, long operationId, String resource) throws Exception {
    entry(updateId, 1, operationId, "{\"state\":" + resource + "}");
  }

  private void write(UUID updateId, String resources) throws Exception {
    entry(updateId, 5, 0L, "{\"newSnapshot\":{\"resources\":" + resources + "}}");
  }

  private void entry(UUID updateId, int kind, Long operationId, String payload) throws Exception {
    entry(updateId, kind, operationId, null, null, payload);
  }

  private void entry(
      UUID updateId, int kind, Long operationId, String promoted, Long value, String payload)
      throws Exception {
    long seq = ++sequence;
    JsonNode parsed = MAPPER.readTree(payload);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var q =
                  em.createNativeQuery(
                          "INSERT INTO journal_entries "
                              + "(id, stack_id, update_id, sequence_id, wire_sequence_id, kind, operation_id, "
                              + promotedColumn(promoted)
                              + "payload) "
                              + "VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6, "
                              + promotedValue(promoted)
                              + "CAST(?7 AS jsonb))")
                      .setParameter(1, UUID.randomUUID())
                      .setParameter(2, stackId)
                      .setParameter(3, updateId)
                      .setParameter(4, seq)
                      .setParameter(5, kind)
                      .setParameter(6, operationId)
                      .setParameter(7, parsed.toString());
              if (promoted != null) q.setParameter(8, value);
              q.executeUpdate();
            });
  }

  private static String promotedColumn(String promoted) {
    return promoted == null ? "" : promoted + ", ";
  }

  private static String promotedValue(String promoted) {
    return promoted == null ? "" : "?8, ";
  }

  private JsonNode find(JsonNode resources, String name) {
    String urn = urn(name);
    for (JsonNode resource : resources) {
      if (urn.equals(resource.required("urn").asText())) return resource;
    }
    throw new AssertionError("resource not found: " + urn);
  }

  private String res(String name) {
    return resource(name, "");
  }

  private String resource(String name, String extra) {
    String fields =
        "\"urn\":\"" + urn(name) + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true";
    if (!extra.isBlank()) fields += "," + extra;
    return "{" + fields + "}";
  }

  private String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }
}
