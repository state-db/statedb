package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeMetadataValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void changedConstructorSignatureReportsTheExactStaleRegistration() throws Exception {
    var metadata =
        MAPPER.readTree(
            """
            {
              "reflection": [{
                "type": "java.util.ArrayList",
                "methods": [{
                  "name": "<init>",
                  "parameterTypes": ["java.lang.String"]
                }]
              }]
            }
            """);

    assertEquals(
        List.of(
            "native metadata test/reachability-metadata.json declares missing constructor "
                + "java.util.ArrayList(java.lang.String)"),
        NativeMetadataValidator.validate("test/reachability-metadata.json", metadata, Set.of()));
  }

  @Test
  void resourcePatternThatMatchesNothingReportsTheMetadataLocation() throws Exception {
    var metadata =
        MAPPER.readTree(
            """
            {
              "resources": [{"glob": "policies/*.cedar"}]
            }
            """);

    assertEquals(
        List.of(
            "native metadata test/reachability-metadata.json resource glob "
                + "policies/*.cedar matches no production resource"),
        NativeMetadataValidator.validate(
            "test/reachability-metadata.json",
            metadata,
            Set.of("policies/templates/example.cedar")));
  }

  @Test
  void changedFieldNameReportsTheExactStaleRegistration() throws Exception {
    var metadata =
        MAPPER.readTree(
            """
            {
              "reflection": [{
                "type": "java.lang.Integer",
                "fields": [{"name": "renamedValue"}]
              }]
            }
            """);

    assertEquals(
        List.of(
            "native metadata test/reachability-metadata.json declares missing field "
                + "java.lang.Integer.renamedValue"),
        NativeMetadataValidator.validate("test/reachability-metadata.json", metadata, Set.of()));
  }

  @Test
  void applicationReflectionTypeIsRejectedFromCentralMetadata() throws Exception {
    var metadata =
        MAPPER.readTree(
            """
            {
              "reflection": [{
                "type": "app.statedb.persistence.stack.SnapshotRetentionRow",
                "allDeclaredConstructors": true
              }]
            }
            """);

    assertEquals(
        List.of(
            "native metadata test/reachability-metadata.json centrally registers application type "
                + "app.statedb.persistence.stack.SnapshotRetentionRow; use a source-local Quarkus "
                + "native annotation"),
        NativeMetadataValidator.validate("test/reachability-metadata.json", metadata, Set.of()));
  }
}
