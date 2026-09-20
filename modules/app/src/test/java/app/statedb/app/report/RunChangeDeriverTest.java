package app.statedb.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.events.StoredEvent;
import app.statedb.persistence.report.RunChangeDeriver;
import app.statedb.persistence.report.RunChanges;
import app.statedb.persistence.report.SecretSentinel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunChangeDeriverTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final RunChangeDeriver deriver = new RunChangeDeriver();

  @Test
  void derivesAndRedactsTheOnePropertyModelUsedByBothSurfaces() throws Exception {
    String secret =
        "{\""
            + SecretSentinel.SIG_KEY
            + "\":\""
            + SecretSentinel.OUTPUT_VALUE
            + "\",\"secret\":true,\"value\":\"do-not-render\"}";
    JsonNode metadata =
        mapper.readTree(
            """
            {
              "op":"update",
              "urn":"urn:pulumi:dev::app::pkg:index:Thing::main",
              "type":"pkg:index:Thing",
              "old":{
                "inputs":{"plain":"old","__internal":"old"},
                "outputs":{"password":%s,"hidden":"old"},
                "hideDiffs":["hidden"]
              },
              "new":{
                "inputs":{"plain":"new","password":"replacement","hidden":"new"},
                "hideDiffs":["hidden"]
              },
              "detailedDiff":{
                "plain":{"diffKind":"update","inputDiff":true},
                "password":{"diffKind":"update","inputDiff":false},
                "hidden":{"diffKind":"update","inputDiff":false},
                "__internal":{"diffKind":"delete","inputDiff":true}
              }
            }
            """
                .formatted(secret));

    RunChanges changes = deriver.derive(List.of(event(1, "resourcePre", metadata)), 32, false);

    assertEquals(1, changes.changed().size());
    RunChanges.ResourceChange resource = changes.changed().getFirst();
    assertEquals("main", resource.name());
    assertEquals("update", resource.op());
    assertEquals(
        List.of("__internal", "password", "plain"),
        resource.properties().stream().map(RunChanges.PropertyChange::path).toList());
    assertTrue(property(resource, "__internal").internal());
    assertTrue(property(resource, "password").secret());
    assertEquals("\"[secret]\"", property(resource, "password").oldValue().toString());
    assertFalse(
        resource.properties().stream().anyMatch(property -> "hidden".equals(property.path())));
  }

  @Test
  void foldsReplacementSubstepsAndKeepsThePreEventDiff() throws Exception {
    String urn = "urn:pulumi:dev::app::pkg:index:Thing::main";
    JsonNode create =
        mapper.readTree(
            """
            {"op":"create-replacement","urn":"%s","type":"pkg:index:Thing",
             "old":{"inputs":{"size":1}},"new":{"inputs":{"size":2}},"diffs":["size"]}
            """
                .formatted(urn));
    JsonNode delete =
        mapper.readTree(
            """
            {"op":"delete-replaced","urn":"%s","type":"pkg:index:Thing"}
            """
                .formatted(urn));

    RunChanges changes =
        deriver.derive(
            List.of(event(1, "resourcePre", create), event(2, "resOutputs", delete)), 32, false);

    assertEquals(1, changes.changed().size());
    assertEquals("replace", changes.changed().getFirst().op());
    assertEquals("size", changes.changed().getFirst().properties().getFirst().path());
  }

  @Test
  void keepsARefreshWithARealDiffAheadOfATerminalSameVisit() throws Exception {
    String urn = "urn:pulumi:dev::app::pkg:index:Thing::main";
    JsonNode refresh =
        mapper.readTree(
            """
            {"op":"refresh","urn":"%s","type":"pkg:index:Thing",
             "old":{"inputs":{"size":1}},"new":{"inputs":{"size":2}},"diffs":["size"]}
            """
                .formatted(urn));
    JsonNode same =
        mapper.readTree(
            """
            {"op":"same","urn":"%s","type":"pkg:index:Thing"}
            """
                .formatted(urn));

    RunChanges changes =
        deriver.derive(
            List.of(event(1, "resourcePre", refresh), event(2, "resOutputs", same)), 32, false);

    assertEquals(1, changes.changed().size());
    assertEquals("refresh", changes.changed().getFirst().op());
  }

  @Test
  void classifiesNoDiffRefreshByTheRunKind() throws Exception {
    String urn = "urn:pulumi:dev::app::pkg:index:Thing::main";
    JsonNode refresh =
        mapper.readTree(
            """
            {"op":"refresh","urn":"%s","type":"pkg:index:Thing"}
            """
                .formatted(urn));
    JsonNode same =
        mapper.readTree(
            """
            {"op":"same","urn":"%s","type":"pkg:index:Thing"}
            """
                .formatted(urn));
    List<StoredEvent> events =
        List.of(event(1, "resourcePre", refresh), event(2, "resOutputs", same));

    RunChanges refreshPhase = deriver.derive(events, 32, false);
    assertEquals(1, refreshPhase.activity().size());
    assertEquals("refresh", refreshPhase.activity().getFirst().op());

    RunChanges standaloneRefresh = deriver.derive(events, 32, true);
    assertEquals(1, standaloneRefresh.unchanged().size());
    assertEquals("same", standaloneRefresh.unchanged().getFirst().op());
  }

  @Test
  void derivesFinalStackOutputsAndMasksUnknownAndSecretValues() throws Exception {
    JsonNode metadata =
        mapper.readTree(
            """
            {"op":"same","urn":"urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev",
             "type":"pulumi:pulumi:Stack",
             "old":{"outputs":{"endpoint":"old","removed":1}},
             "new":{"outputs":{
               "endpoint":"new",
               "token":"04da6b54-80e4-46f7-96ec-b56ff0331ba9",
               "password":{"%s":"%s","value":"do-not-render"}
             }}}
            """
                .formatted(SecretSentinel.SIG_KEY, SecretSentinel.SECRET));

    RunChanges changes = deriver.derive(List.of(event(1, "resOutputs", metadata)), 32, false);

    assertEquals(
        List.of("endpoint", "password", "removed", "token"),
        changes.outputs().stream().map(RunChanges.OutputChange::key).toList());
    assertEquals("update", output(changes, "endpoint").kind());
    assertEquals("remove", output(changes, "removed").kind());
    assertEquals("\"[unknown]\"", output(changes, "token").newValue().toString());
    assertEquals("\"[secret]\"", output(changes, "password").newValue().toString());
  }

  private StoredEvent event(int sequence, String kind, JsonNode metadata) {
    ObjectNode payload = mapper.createObjectNode();
    payload.set(kind + "Event", mapper.createObjectNode().set("metadata", metadata));
    return new StoredEvent(sequence, kind, null, payload);
  }

  private static RunChanges.PropertyChange property(
      RunChanges.ResourceChange resource, String path) {
    return resource.properties().stream()
        .filter(property -> path.equals(property.path()))
        .findFirst()
        .orElseThrow();
  }

  private static RunChanges.OutputChange output(RunChanges changes, String key) {
    return changes.outputs().stream()
        .filter(output -> key.equals(output.key()))
        .findFirst()
        .orElseThrow();
  }
}
