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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class StackAssemblerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "assembler-org";

  private enum JournalKind {
    BEGIN(0),
    SUCCESS(1),
    FAILURE(2),
    REFRESH_SUCCESS(3),
    OUTPUTS(4),
    WRITE(5),
    SECRETS_MANAGER(6),
    REBUILT_BASE_STATE(7),
    UNKNOWN(99);

    private final int wireValue;

    JournalKind(int wireValue) {
      this.wireValue = wireValue;
    }
  }

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
    begin(update, 1L, "creating", res("a"));
    entry(update, JournalKind.SUCCESS, 1L, "{\"state\":" + res("a") + "}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
  }

  @Test
  void successRemoveOldDropsBaseAtIndex() throws Exception {
    UUID update = update();
    write(update, "[" + res("a") + "," + res("b") + "]");
    entry(update, JournalKind.SUCCESS, 1L, "remove_old", 1L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
  }

  @Test
  void successRemoveNewDropsNewByOpId() throws Exception {
    UUID update = update();
    success(update, 1L, res("a"));
    success(update, 2L, res("b"));
    entry(update, JournalKind.SUCCESS, 3L, "remove_new", 2L, "{}");

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
    entry(update, JournalKind.SUCCESS, 3L, "delete_old", 0L, "{}");
    entry(update, JournalKind.SUCCESS, 4L, "pending_replacement_old", 1L, "{}");
    entry(update, JournalKind.SUCCESS, 5L, "delete_new", 1L, "{}");
    entry(update, JournalKind.SUCCESS, 6L, "pending_replacement_new", 2L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertTrue(find(resources, "new-delete").required("delete").asBoolean());
    assertTrue(find(resources, "new-pending").required("pendingReplacement").asBoolean());
    assertTrue(find(resources, "base-delete").required("delete").asBoolean());
    assertTrue(find(resources, "base-pending").required("pendingReplacement").asBoolean());
  }

  @Test
  void failureClearsOnlyMatchingPendingOp() throws Exception {
    UUID update = update();
    begin(update, 1L, "creating", res("a"));
    begin(update, 2L, "creating", res("b"));
    entry(update, JournalKind.FAILURE, 1L, "{}");

    JsonNode pending = assembler.assemble(stackId).deployment().required("pending_operations");

    assertEquals(1, pending.size());
    assertEquals(urn("b"), pending.get(0).required("resource").required("urn").asText());
  }

  @Test
  void failedCreateLeavesNoResourceAndNoPendingOp() throws Exception {
    UUID update = update();
    begin(update, 1L, "creating", res("orphan"));
    entry(update, JournalKind.FAILURE, 1L, "{}");

    JsonNode deployment = assembler.assemble(stackId).deployment();

    assertEquals(0, deployment.required("resources").size());
    assertFalse(deployment.has("pending_operations"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"updating", "reading", "deleting"})
  void failedTerminalIgnoresPromotedFieldsAndClearsPendingOp(String opType) throws Exception {
    // A Failure only clears the in-flight operation; it must not apply the index fields that
    // Success/RefreshSuccess honor. Applying them would mutate or drop resources that may still
    // exist in the cloud. Seed both a base resource and a Success-created new resource so the
    // Failure targets both halves of the reducer's index space.
    UUID update = update();
    write(update, "[" + res("base") + "]");
    success(update, 10L, res("new"));
    begin(update, 11L, opType, res("base"));
    failureWithAllPromoted(update, 11L, 0L, 10L, 0L, 10L, 0L, 10L);

    JsonNode deployment = assembler.assemble(stackId).deployment();
    JsonNode resources = deployment.required("resources");

    assertEquals(2, resources.size());
    for (JsonNode r : resources) {
      assertFalse(r.has("delete"));
      assertFalse(r.has("pendingReplacement"));
    }
    assertEquals(urn("base"), find(resources, "base").required("urn").asText());
    assertEquals(urn("new"), find(resources, "new").required("urn").asText());
    assertFalse(deployment.has("pending_operations"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"creating", "updating", "deleting", "reading", "importing"})
  void allBeginOpTypesSurfaceAsPendingOperations(String opType) throws Exception {
    UUID update = update();
    begin(update, 1L, opType, res("r"));

    JsonNode pending = assembler.assemble(stackId).deployment().required("pending_operations");

    assertEquals(1, pending.size());
    assertEquals(opType, pending.get(0).required("type").asText());
    assertEquals(urn("r"), pending.get(0).required("resource").required("urn").asText());
  }

  @Test
  void refreshSuccessClearsPendingOperation() throws Exception {
    UUID update = update();
    begin(update, 1L, "reading", res("r"));
    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "{}");

    JsonNode deployment = assembler.assemble(stackId).deployment();

    assertFalse(deployment.has("pending_operations"));
  }

  @Test
  void refreshSuccessRemoveOldWithStateReplacesBaseAtIndex() throws Exception {
    UUID update = update();
    write(update, "[" + resource("a", "\"sourcePosition\":\"project:///old.ts#1,1\"") + "]");

    entry(
        update,
        JournalKind.REFRESH_SUCCESS,
        1L,
        "remove_old",
        0L,
        "{\"state\":" + resource("a", "\"sourcePosition\":\"project:///refreshed.ts#2,1\"") + "}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(1, countUrn(resources, urn("a")));
    assertEquals(
        "project:///refreshed.ts#2,1", find(resources, "a").required("sourcePosition").asText());
  }

  @Test
  void refreshSuccessRemoveOldWithoutStateDeletesBaseAtIndex() throws Exception {
    UUID update = update();
    write(update, "[" + res("removed") + "," + res("survivor") + "]");

    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "remove_old", 0L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("survivor"), resources.get(0).required("urn").asText());
  }

  @Test
  void refreshSuccessRemoveNewReplacesNewResourceByOperationId() throws Exception {
    UUID update = update();
    success(update, 1L, resource("a", "\"sourcePosition\":\"project:///old.ts#1,1\""));

    entry(
        update,
        JournalKind.REFRESH_SUCCESS,
        1L,
        "remove_new",
        1L,
        "{\"state\":" + resource("a", "\"sourcePosition\":\"project:///refreshed.ts#2,1\"") + "}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(1, countUrn(resources, urn("a")));
    assertEquals(
        "project:///refreshed.ts#2,1", find(resources, "a").required("sourcePosition").asText());
  }

  @Test
  void refreshSuccessRemoveNewWithoutStateRemovesNewResourceByOperationId() throws Exception {
    UUID update = update();
    success(update, 1L, res("removed"));
    success(update, 2L, res("survivor"));

    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "remove_new", 1L, "{}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("survivor"), resources.get(0).required("urn").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"updating", "reading", "deleting", "importing"})
  void nonCreatingBasePendingOperationsAreDropped(String opType) throws Exception {
    UUID update = update();
    entry(
        update,
        JournalKind.WRITE,
        0L,
        "{\"newSnapshot\":{\"resources\":[],\"pending_operations\":["
            + "{\"type\":\""
            + opType
            + "\",\"resource\":"
            + res("x")
            + "}]}}");

    JsonNode deployment = assembler.assemble(stackId).deployment();

    assertFalse(deployment.has("pending_operations"));
  }

  @Test
  void refreshSuccessRebuildsAllDanglingReferencesTogether() throws Exception {
    UUID update = update();
    write(
        update,
        "["
            + res("parent")
            + ","
            + res("keeper")
            + ","
            + resource(
                "child",
                "\"parent\":\""
                    + urn("parent")
                    + "\",\"dependencies\":[\""
                    + urn("parent")
                    + "\"],\"propertyDependencies\":{\"input\":[\""
                    + urn("parent")
                    + "\",\""
                    + urn("keeper")
                    + "\"]},\"replaceWith\":[\""
                    + urn("parent")
                    + "\"],\"deletedWith\":\""
                    + urn("parent")
                    + "\"")
            + "]");
    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "remove_old", 0L, "{}");

    JsonNode child = find(assembler.assemble(stackId).deployment().required("resources"), "child");
    JsonNode inputDeps = child.required("propertyDependencies").required("input");

    assertFalse(child.required("dependencies").elements().hasNext());
    assertEquals(1, inputDeps.size());
    assertEquals(urn("keeper"), inputDeps.get(0).asText());
    assertEquals("", child.required("replaceWith").get(0).asText());
    assertEquals("", child.required("deletedWith").asText());
    assertEquals("", child.required("parent").asText());
  }

  @Test
  void refreshSuccessRebuildsPropertyDependenciesAndDeletedWith() throws Exception {
    UUID update = update();
    write(
        update,
        "["
            + res("removed")
            + ","
            + res("keeper")
            + ","
            + resource(
                "dependent",
                "\"propertyDependencies\":{\"input\":[\""
                    + urn("removed")
                    + "\",\""
                    + urn("keeper")
                    + "\"]},\"deletedWith\":\""
                    + urn("removed")
                    + "\"")
            + "]");
    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "remove_old", 0L, "{}");

    JsonNode dependent =
        find(assembler.assemble(stackId).deployment().required("resources"), "dependent");
    JsonNode inputDeps = dependent.required("propertyDependencies").required("input");

    assertEquals(1, inputDeps.size());
    assertEquals(urn("keeper"), inputDeps.get(0).asText());
    assertEquals("", dependent.required("deletedWith").asText());
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
    entry(update, JournalKind.REFRESH_SUCCESS, 1L, "remove_old", 0L, "{}");

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
        JournalKind.OUTPUTS,
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
    entry(
        update,
        JournalKind.SECRETS_MANAGER,
        0L,
        "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"u\"}}}");
    // Second entry has matching type+state but adds an extraneous field. The
    // elide branch (sameSecretsProvider) compares only type+state, so a working
    // elision keeps the first provider node verbatim and the extra field never
    // makes it into the assembled output. Without elision, the second entry
    // wholesale-replaces secrets_providers and `extra` would leak through.
    entry(
        update,
        JournalKind.SECRETS_MANAGER,
        0L,
        "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"u\"},\"extra\":\"x\"}}");

    JsonNode provider = assembler.assemble(stackId).deployment().required("secrets_providers");

    assertEquals("service", provider.required("type").asText());
    assertFalse(provider.has("extra"));
  }

  @Test
  void secretsManagerReplacesChangedProviderAndCanClearIt() throws Exception {
    UUID update = update();
    entry(
        update,
        JournalKind.SECRETS_MANAGER,
        0L,
        "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"service-a\"}}}");
    entry(
        update,
        JournalKind.SECRETS_MANAGER,
        0L,
        "{\"secretsProvider\":{\"type\":\"service\",\"state\":{\"url\":\"service-b\"}}}");

    JsonNode replaced = assembler.assemble(stackId).deployment().required("secrets_providers");

    assertEquals("service", replaced.required("type").asText());
    assertEquals("service-b", replaced.required("state").required("url").asText());

    entry(
        update,
        JournalKind.SECRETS_MANAGER,
        0L,
        "{\"secretsProvider\":{\"type\":\"passphrase\",\"state\":{\"salt\":\"abc\"}}}");

    JsonNode changedType = assembler.assemble(stackId).deployment().required("secrets_providers");

    assertEquals("passphrase", changedType.required("type").asText());
    assertEquals("abc", changedType.required("state").required("salt").asText());

    entry(update, JournalKind.SECRETS_MANAGER, 0L, "{\"secretsProvider\":null}");

    assertFalse(assembler.assemble(stackId).deployment().has("secrets_providers"));
  }

  @Test
  void encryptedSecretValuesArePreservedInJournalStateEntries() throws Exception {
    JsonNode expected = MAPPER.readTree(secretValue("ciphertext-1"));
    UUID update = update();

    success(update, 1L, resource("component", "\"inputs\":{\"password\":" + expected + "}"));
    success(update, 2L, resource("stack", "\"type\":\"pulumi:pulumi:Stack\""));
    entry(
        update,
        JournalKind.OUTPUTS,
        2L,
        "remove_new",
        2L,
        "{\"state\":"
            + resource(
                "stack",
                "\"type\":\"pulumi:pulumi:Stack\",\"outputs\":{\"password\":" + expected + "}")
            + "}");

    JsonNode beforeWrite = assembler.assemble(stackId).deployment().required("resources");
    assertEquals(expected, find(beforeWrite, "component").required("inputs").required("password"));
    assertEquals(expected, find(beforeWrite, "stack").required("outputs").required("password"));

    write(
        update, "[" + resource("from-write", "\"outputs\":{\"password\":" + expected + "}") + "]");

    JsonNode afterWrite = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(expected, find(afterWrite, "from-write").required("outputs").required("password"));
  }

  @Test
  void rebuiltBaseStatePromotesNewToBase() throws Exception {
    UUID update = update();
    success(update, 1L, res("a"));
    entry(update, JournalKind.REBUILT_BASE_STATE, 0L, "{}");
    success(update, 2L, res("b"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(2, resources.size());
    assertEquals(urn("b"), resources.get(0).required("urn").asText());
    assertEquals(urn("a"), resources.get(1).required("urn").asText());
  }

  @Test
  void outOfOrderRebuiltBaseStateWithLaterRemoveNewDoesNotCrashExport() throws Exception {
    UUID update = update();
    success(update, 1L, res("a"));
    entry(update, JournalKind.REBUILT_BASE_STATE, 0L, "{}");
    entry(
        update,
        JournalKind.REFRESH_SUCCESS,
        2L,
        "remove_new",
        1L,
        "{\"state\":" + res("a-refreshed") + "}");

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
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

  // A same-step success can carry updated checkpoint metadata; remove_old must make that state
  // replace the base row instead of appending a duplicate resource with the same URN.
  @Test
  void sameSuccessReplacesBaseResourceMetadataWithoutDuplicatingResource() throws Exception {
    UUID update = update();
    write(update, "[" + resource("a", "\"sourcePosition\":\"project:///old.ts#1,1\"") + "]");

    // Pulumi SameStep is represented here as Begin followed by Success(remove_old).
    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(
        update, 1L, 0L, resource("a", "\"sourcePosition\":\"project:///new.ts#2,1\""));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(1, resources.size());
    assertEquals(1, countUrn(resources, urn("a")));
    assertEquals(urn("a"), resources.get(0).required("urn").asText());
    assertEquals("project:///new.ts#2,1", resources.get(0).required("sourcePosition").asText());
  }

  // Even if a run only has elidable same-step writes, replay must still produce a normal final
  // deployment with no leftover pending operations or duplicate base/new resources.
  @Test
  void onlyElidedSameEntriesStillProduceValidFinalExport() throws Exception {
    UUID update = update();
    write(update, "[" + res("a") + "," + res("b") + "]");

    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(update, 1L, 0L, res("a"));
    entry(update, JournalKind.BEGIN, 2L, "{}");
    successReplaceOld(update, 2L, 1L, res("b"));

    JsonNode deployment = assembler.assemble(stackId).deployment();
    JsonNode resources = deployment.required("resources");

    assertEquals(2, resources.size());
    assertFalse(deployment.has("pending_operations"));
    assertEquals(1, countUrn(resources, urn("a")));
    assertEquals(1, countUrn(resources, urn("b")));
    assertEquals("v3.242.0", deployment.required("manifest").required("version").asText());
  }

  // Dependency-only same changes are real checkpoint changes: each success replaces its old base
  // index, and the merged export must preserve Pulumi's final topological order.
  @Test
  void sameDependencyChangesReplaceBaseAndPreserveFinalOrder() throws Exception {
    UUID update = update();
    write(
        update,
        "[" + res("a") + "," + resource("b", "\"dependencies\":[\"" + urn("a") + "\"]") + "]");

    // Each SameStep is represented as Begin followed by Success(remove_old).
    entry(update, JournalKind.BEGIN, 2L, "{}");
    successReplaceOld(update, 2L, 1L, res("b"));
    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(update, 1L, 0L, resource("a", "\"dependencies\":[\"" + urn("b") + "\"]"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

    assertEquals(2, resources.size());
    assertEquals(urn("b"), resources.get(0).required("urn").asText());
    assertFalse(resources.get(0).has("dependencies"));
    assertEquals(urn("a"), resources.get(1).required("urn").asText());
    assertEquals(urn("b"), resources.get(1).required("dependencies").get(0).asText());
  }

  // Pulumi's vexing deployment merge keeps both versions of a replaced URN: the live replacement
  // appears in the current-plan DAG, while the old base resource is preserved as delete=true after
  // untouched base-only dependencies that it still references.
  @Test
  void vexingReplacementMergePreservesTopologicalOrderAndDeletionState() throws Exception {
    UUID update = update();
    write(
        update,
        "["
            + res("a")
            + ","
            + resource("b", "\"dependencies\":[\"" + urn("a") + "\"]")
            + ","
            + resource("c", "\"dependencies\":[\"" + urn("a") + "\",\"" + urn("b") + "\"]")
            + ","
            + resource("d", "\"dependencies\":[\"" + urn("c") + "\"]")
            + ","
            + resource("e", "\"dependencies\":[\"" + urn("c") + "\"]")
            + "]");

    // SameStep for b removes its dependency on a.
    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(update, 1L, 1L, res("b"));

    // CreateReplacement for c creates the new c and marks old c as pending deletion.
    begin(update, 2L, "creating", resource("c", "\"dependencies\":[\"" + urn("b") + "\"]"));
    entry(
        update,
        JournalKind.SUCCESS,
        2L,
        "delete_old",
        2L,
        "{\"state\":" + resource("c", "\"dependencies\":[\"" + urn("b") + "\"]") + "}");

    // Replace itself produces no journal row; the old c remains until a later delete step.

    // Update d points at the replacement c in the current-plan DAG.
    begin(update, 3L, "updating", resource("d", "\"dependencies\":[\"" + urn("c") + "\"]"));
    successReplaceOld(update, 3L, 3L, resource("d", "\"dependencies\":[\"" + urn("c") + "\"]"));

    JsonNode deployment = assembler.assemble(stackId).deployment();
    JsonNode resources = deployment.required("resources");

    assertEquals(6, resources.size());
    assertEquals(urn("b"), resources.get(0).required("urn").asText());
    assertEquals(urn("c"), resources.get(1).required("urn").asText());
    assertEquals(urn("d"), resources.get(2).required("urn").asText());
    assertEquals(urn("a"), resources.get(3).required("urn").asText());
    assertEquals(urn("c"), resources.get(4).required("urn").asText());
    assertEquals(urn("e"), resources.get(5).required("urn").asText());

    assertFalse(resources.get(0).has("dependencies"));
    assertEquals(urn("b"), resources.get(1).required("dependencies").get(0).asText());
    assertEquals(urn("c"), resources.get(2).required("dependencies").get(0).asText());
    assertFalse(resources.get(3).has("dependencies"));
    assertTrue(resources.get(4).required("delete").asBoolean());
    assertEquals(urn("a"), resources.get(4).required("dependencies").get(0).asText());
    assertEquals(urn("b"), resources.get(4).required("dependencies").get(1).asText());
    assertEquals(urn("c"), resources.get(5).required("dependencies").get(0).asText());
    assertFalse(deployment.has("pending_operations"));
    assertReferencesPointToPriorResources(resources);
  }

  // Non-dependency same changes should be carried by the replacement state, not lost because the
  // engine called the step "same".
  @Test
  void meaningfulSameChangesReplaceBaseResource() throws Exception {
    UUID update = update();
    String provider1 = "urn:pulumi:dev::proj::pulumi:providers:pkg::provider::id1";
    String provider2 = "urn:pulumi:dev::proj::pulumi:providers:pkg::provider2::id2";
    write(
        update,
        "["
            + resourceWithUrn(
                urn("provider"), "pulumi:providers:pkg", "\"custom\":true,\"id\":\"id1\"")
            + ","
            + resourceWithUrn(urn("parent"), "pkg:index:Parent", "\"custom\":true")
            + ","
            + resource("a", "\"provider\":\"" + provider1 + "\"")
            + "]");

    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(
        update,
        1L,
        2L,
        resourceWithUrn(
            urn("a"),
            "pkg:index:a",
            "\"custom\":false,\"provider\":\""
                + provider2
                + "\",\"parent\":\""
                + urn("parent")
                + "\",\"protect\":true,\"outputs\":{\"name\":\"changed\"}"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");
    JsonNode a = find(resources, "a");

    assertEquals(3, resources.size());
    assertFalse(a.required("custom").asBoolean());
    assertEquals(provider2, a.required("provider").asText());
    assertEquals(urn("parent"), a.required("parent").asText());
    assertTrue(a.required("protect").asBoolean());
    assertEquals("changed", a.required("outputs").required("name").asText());
  }

  // A same-step alias/URN change must flow through the normal Pulumi reference-normalization path
  // so dependents point at the canonical URN and transient aliases do not leak into export.
  @Test
  void sameAliasChangesRewriteUrnReferences() throws Exception {
    UUID update = update();
    String oldUrn = urn("old-a");
    String newUrn = urn("a");
    write(
        update,
        "["
            + resourceWithUrn(oldUrn, "pkg:index:a", "\"custom\":true")
            + ","
            + resource("dependent", "\"dependencies\":[\"" + oldUrn + "\"]")
            + "]");

    entry(update, JournalKind.BEGIN, 1L, "{}");
    successReplaceOld(
        update,
        1L,
        0L,
        resourceWithUrn(newUrn, "pkg:index:a", "\"custom\":true,\"aliases\":[\"" + oldUrn + "\"]"));

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");
    JsonNode a = find(resources, "a");
    JsonNode dependent = find(resources, "dependent");

    assertEquals(2, resources.size());
    assertEquals(newUrn, a.required("urn").asText());
    assertFalse(a.has("aliases"));
    assertEquals(newUrn, dependent.required("dependencies").get(0).asText());
  }

  // Source-position-only edits should not force an intermediate replacement; the metadata changes
  // only when Pulumi's close flush writes the final snapshot.
  @Test
  void sourcePositionOnlySameDoesNotReplaceBaseUntilCloseFlush() throws Exception {
    UUID update = update();
    write(update, "[" + resource("a", "\"sourcePosition\":\"project:///old.ts#1,1\"") + "]");

    JsonNode beforeFlush = assembler.assemble(stackId).deployment().required("resources");
    assertEquals(1, beforeFlush.size());
    assertEquals(
        "project:///old.ts#1,1", find(beforeFlush, "a").required("sourcePosition").asText());

    write(update, "[" + resource("a", "\"sourcePosition\":\"project:///new.ts#1,1\"") + "]");

    JsonNode afterFlush = assembler.assemble(stackId).deployment().required("resources");
    assertEquals(1, afterFlush.size());
    assertEquals(1, countUrn(afterFlush, urn("a")));
    assertEquals(
        "project:///new.ts#1,1", find(afterFlush, "a").required("sourcePosition").asText());
  }

  @Test
  void unknownKindThrowsWithContext() throws Exception {
    UUID update = update();
    entry(update, JournalKind.UNKNOWN, 0L, "{}");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(stackId));

    assertTrue(e.getMessage().contains("stack=" + stackId));
    assertTrue(e.getMessage().contains("update=" + update));
    assertTrue(e.getMessage().contains("kind=" + JournalKind.UNKNOWN.wireValue));
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

  private void begin(UUID updateId, long operationId, String operationType, String resource)
      throws Exception {
    entry(
        updateId,
        JournalKind.BEGIN,
        operationId,
        "{\"operation\":{\"resource\":" + resource + ",\"type\":\"" + operationType + "\"}}");
  }

  private void success(UUID updateId, long operationId, String resource) throws Exception {
    entry(updateId, JournalKind.SUCCESS, operationId, "{\"state\":" + resource + "}");
  }

  private void successReplaceOld(UUID updateId, long operationId, long removeOld, String resource)
      throws Exception {
    entry(
        updateId,
        JournalKind.SUCCESS,
        operationId,
        "remove_old",
        removeOld,
        "{\"state\":" + resource + "}");
  }

  private void write(UUID updateId, String resources) throws Exception {
    entry(updateId, JournalKind.WRITE, 0L, "{\"newSnapshot\":{\"resources\":" + resources + "}}");
  }

  private void entry(UUID updateId, JournalKind kind, long operationId, String payload)
      throws Exception {
    entry(updateId, kind, operationId, null, (Long) null, payload);
  }

  /**
   * Inserts a Failure entry carrying every promoted index field at once. Used to prove that the
   * reducer's Failure branch ignores them all; a regression that starts honoring any single field
   * would flip an assertion in the calling test.
   */
  private void failureWithAllPromoted(
      UUID updateId,
      long operationId,
      Long removeOld,
      Long removeNew,
      Long deleteOld,
      Long deleteNew,
      Long pendingReplacementOld,
      Long pendingReplacementNew)
      throws Exception {
    long seq = ++sequence;
    JsonNode parsed = MAPPER.readTree("{}");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "INSERT INTO journal_entries "
                            + "(id, stack_id, update_id, sequence_id, wire_sequence_id, kind, "
                            + " operation_id, remove_old, remove_new, delete_old, delete_new, "
                            + " pending_replacement_old, pending_replacement_new, payload) "
                            + "VALUES (?1, ?2, ?3, ?4, ?4, "
                            + JournalKind.FAILURE.wireValue
                            + ", ?5, ?6, ?7, ?8, ?9, ?10, ?11, "
                            + " CAST(?12 AS jsonb))")
                    .setParameter(1, UUID.randomUUID())
                    .setParameter(2, stackId)
                    .setParameter(3, updateId)
                    .setParameter(4, seq)
                    .setParameter(5, operationId)
                    .setParameter(6, removeOld)
                    .setParameter(7, removeNew)
                    .setParameter(8, deleteOld)
                    .setParameter(9, deleteNew)
                    .setParameter(10, pendingReplacementOld)
                    .setParameter(11, pendingReplacementNew)
                    .setParameter(12, parsed.toString())
                    .executeUpdate());
  }

  private void entry(
      UUID updateId,
      JournalKind kind,
      long operationId,
      String promoted,
      Long value,
      String payload)
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
                      .setParameter(5, kind.wireValue)
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

  private int countUrn(JsonNode resources, String urn) {
    int count = 0;
    for (JsonNode resource : resources) {
      if (urn.equals(resource.required("urn").asText())) count++;
    }
    return count;
  }

  private void assertReferencesPointToPriorResources(JsonNode resources) {
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (JsonNode resource : resources) {
      String urn = resource.required("urn").asText();
      assertUrnArrayReferencesSeen(resource, "dependencies", seen);
      assertUrnArrayReferencesSeen(resource, "replaceWith", seen);

      String parent = resource.path("parent").asText("");
      if (!parent.isEmpty()) assertTrue(seen.contains(parent), "parent not seen before " + urn);

      String provider = resource.path("provider").asText("");
      if (!provider.isEmpty()) {
        int sep = provider.lastIndexOf("::");
        String providerUrn = sep < 0 ? provider : provider.substring(0, sep);
        assertTrue(seen.contains(providerUrn), "provider not seen before " + urn);
      }

      seen.add(urn);
    }
  }

  private void assertUrnArrayReferencesSeen(
      JsonNode resource, String field, java.util.Set<String> seen) {
    JsonNode values = resource.get(field);
    if (values == null || !values.isArray()) return;
    for (JsonNode value : values) {
      String urn = value.asText("");
      if (!urn.isEmpty()) {
        assertTrue(
            seen.contains(urn),
            field + " reference not seen before " + resource.required("urn").asText() + ": " + urn);
      }
    }
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

  private String resourceWithUrn(String urn, String type, String extra) {
    String fields = "\"urn\":\"" + urn + "\",\"type\":\"" + type + "\"";
    if (!extra.isBlank()) fields += "," + extra;
    return "{" + fields + "}";
  }

  private String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }

  private String secretValue(String ciphertext) {
    return "{\"4dabf18193072939515e22adb298388d\":\"1b47061264138c4ac30d75fd1eb44270\","
        + "\"ciphertext\":\""
        + ciphertext
        + "\"}";
  }
}
