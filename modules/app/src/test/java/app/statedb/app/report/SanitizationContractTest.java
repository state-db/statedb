package app.statedb.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.report.ChangeReport;
import app.statedb.persistence.report.Markdown;
import app.statedb.persistence.report.PropertyPath;
import app.statedb.persistence.report.ReportLimits;
import app.statedb.persistence.report.ReportText;
import app.statedb.persistence.report.SecretSentinel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sanitization contract of {@code design/pr-enrichment.md}, tested as the security surface it
 * is rather than as renderer polish.
 *
 * <p>No Quarkus context: every class under test is pure, and keeping these tests out of the
 * container is the point — they are the ones that must be cheap enough to keep adding cases to.
 */
class SanitizationContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Nested
  class ChangeReportBoundary {

    @Test
    void rejectsStringsThatBypassNeutralization() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ChangeReport.Coordinates("org\n@everyone", "project", "stack"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ChangeReport.PropertyChange(
                  "safe" + new String(Character.toChars(0x202e)) + "hidden",
                  ChangeReport.DiffKind.UPDATE,
                  false));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ChangeReport.PropertyChange(
                  "x".repeat(ReportLimits.MAX_FIELD_CODE_POINTS + 1),
                  ChangeReport.DiffKind.UPDATE,
                  false));
    }

    @Test
    void diagnosticSeverityIsClosed() throws Exception {
      assertEquals(
          ChangeReport.DiagnosticSeverity.UNKNOWN,
          ChangeReport.DiagnosticSeverity.fromWire("warning\n@everyone"));
      assertEquals(
          "\"unknown\"", MAPPER.writeValueAsString(ChangeReport.DiagnosticSeverity.UNKNOWN));
    }

    @Test
    void operationsAndDiffKindsSerializeWithTheirPulumiWireValues() throws Exception {
      assertEquals(
          "\"create-replacement\"", MAPPER.writeValueAsString(ChangeReport.Op.CREATE_REPLACEMENT));
      assertEquals(
          "\"update-replace\"", MAPPER.writeValueAsString(ChangeReport.DiffKind.UPDATE_REPLACE));
    }
  }

  @Nested
  class SecretRecognition {

    @Test
    void recognisesTheSecretWrapperAsPulumiWritesIt() throws Exception {
      // Exactly the shape in the recorded preview fixture at
      // resourcePreEvent.metadata.old.outputs.password.
      JsonNode secret =
          json(
              """
              {"4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
               "ciphertext": "AAAA"}
              """);
      assertTrue(SecretSentinel.isSecret(secret));
      assertEquals(SecretSentinel.SECRET, SecretSentinel.signatureOf(secret));
    }

    @Test
    void recognisesTheSecondSecretShapeAnOutputValueFlaggedSecret() throws Exception {
      // The shape a redactor that only knows SecretSig leaks: the value here is plaintext.
      JsonNode output =
          json(
              """
              {"4dabf18193072939515e22adb298388d": "d0e6a833031e9bbcd3f4e8bde6ca49a4",
               "secret": true, "value": "hunter2"}
              """);
      assertTrue(SecretSentinel.isSecret(output));
    }

    @Test
    void anOutputValueNotFlaggedSecretIsNotSecret() throws Exception {
      JsonNode output =
          json(
              """
              {"4dabf18193072939515e22adb298388d": "d0e6a833031e9bbcd3f4e8bde6ca49a4",
               "value": "public"}
              """);
      assertFalse(SecretSentinel.isSecret(output));
    }

    @Test
    void findsASecretNestedInsideANonSecretOutputValue() throws Exception {
      JsonNode output =
          json(
              """
              {"4dabf18193072939515e22adb298388d": "d0e6a833031e9bbcd3f4e8bde6ca49a4",
               "value": {"token": {
                 "4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
                 "ciphertext": "AAAA"}}}
              """);

      assertFalse(SecretSentinel.isSecret(output), "the output wrapper itself is not secret");
      assertTrue(
          SecretSentinel.containsSecret(output, 32),
          "Pulumi treats a secret nested in an output's element as contained by the output");
    }

    @Test
    void anUnrecognisedSignatureIsTreatedAsSecret() throws Exception {
      JsonNode future =
          json(
              """
              {"4dabf18193072939515e22adb298388d": "ffffffffffffffffffffffffffffffff"}
              """);
      assertTrue(SecretSentinel.isSecret(future));
    }

    @Test
    void aNonStringSignatureIsNotASignatureAtAll() throws Exception {
      // resource.HasSig requires the value at the key to be a string. An object whose value there
      // is a number is an ordinary object that happens to use the key, and treating it as an
      // unknown signature would redact real data.
      JsonNode numeric =
          json(
              """
          {"4dabf18193072939515e22adb298388d": 1}
          """);
      assertNull(SecretSentinel.signatureOf(numeric));
      assertFalse(SecretSentinel.isSecret(numeric));
    }

    @Test
    void theStringOneIsASignatureAndSoIsTreatedAsSecret() throws Exception {
      // The console's own masker compares this key against the string "1". That comparison is
      // wrong twice over: it does not match the real secret signature, so it misses actual
      // secrets, and "1" is not a signature Pulumi ever emits. Here the fail-safe rule covers it
      // — "1" is a string, therefore a signature, therefore unrecognised, therefore secret.
      JsonNode consoleShape =
          json(
              """
          {"4dabf18193072939515e22adb298388d": "1"}
          """);
      assertEquals("1", SecretSentinel.signatureOf(consoleShape));
      assertTrue(SecretSentinel.isSecret(consoleShape));
    }

    @Test
    void nonSecretSpecialTypesAreNotSecret() throws Exception {
      for (String signature :
          new String[] {
            SecretSentinel.RESOURCE_REFERENCE,
            SecretSentinel.ASSET,
            SecretSentinel.ARCHIVE,
            SecretSentinel.BYTE_STRING
          }) {
        assertFalse(
            SecretSentinel.isSecret(
                json("{\"4dabf18193072939515e22adb298388d\": \"" + signature + "\"}")),
            signature + " must not be treated as a secret");
      }
    }

    @Test
    void findsASecretNestedArbitrarilyDeep() throws Exception {
      // The recorded fixture nests them under __pulumi_raw_state_delta.obj.ps.*, so a top-level
      // scan is not enough.
      JsonNode nested =
          json(
              """
              {"outputs": {"__pulumi_raw_state_delta": {"obj": {"ps": {"keepers":
                {"4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
                 "ciphertext": "AAAA"}}}}}}
              """);
      assertTrue(SecretSentinel.containsSecret(nested, 32));
    }

    @Test
    void findsASecretInsideAnArray() throws Exception {
      JsonNode nested =
          json(
              """
              {"rules": [{"port": 80}, {"token":
                {"4dabf18193072939515e22adb298388d": "1b47061264138c4ac30d75fd1eb44270",
                 "ciphertext": "AAAA"}}]}
              """);
      assertTrue(SecretSentinel.containsSecret(nested, 32));
    }

    @Test
    void anUnexaminedSubtreeAnswersSecret() throws Exception {
      // A hostile payload can nest deeper than any scan will go. Running out of depth must fail
      // closed: we did not look, so we must not claim it is clean.
      StringBuilder deep = new StringBuilder("{\"a\":".repeat(200));
      deep.append("1").append("}".repeat(200));
      assertTrue(SecretSentinel.containsSecret(json(deep.toString()), 8));
    }

    @Test
    void ordinaryDataIsNotSecret() throws Exception {
      assertFalse(
          SecretSentinel.containsSecret(
              json(
                  """
          {"password": "not actually wrapped", "length": 16, "list": [1, 2, 3]}
          """),
              32));
    }
  }

  @Nested
  class TextNeutralization {

    // Codepoints are built with cp() rather than written as \\uXXXX escapes. Java processes a
    // unicode escape in the lexer, before it tokenizes, so the escape would put the real character
    // into this source file — which for a bidi override means this file itself renders
    // misleadingly,
    // and for a NUL means the source does not compile at all.
    private static String cp(int codePoint) {
      return new String(Character.toChars(codePoint));
    }

    private static final int RTL_OVERRIDE = 0x202e;
    private static final int POP_DIRECTIONAL = 0x202c;
    private static final int ZERO_WIDTH_SPACE = 0x200b;
    private static final int ZERO_WIDTH_NON_JOINER = 0x200c;
    private static final int BYTE_ORDER_MARK = 0xfeff;
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int NO_BREAK_SPACE = 0x00a0;
    private static final int HIGH_SURROGATE = 0xd800;
    private static final int GRINNING_FACE = 0x1f600;

    @Test
    void stripsBidiOverridesAndIsolates() {
      // A right-to-left override in a resource name renders the text after it reversed, so a
      // reviewer reads something other than what the URN says.
      String hostile = "prod" + cp(RTL_OVERRIDE) + "deleted-all" + cp(POP_DIRECTIONAL) + "-db";
      String value = ReportText.neutralize(hostile, 256).value();
      value.codePoints().forEach(c -> assertFalse(isBidi(c), "bidi control survived: " + c));
      assertEquals("proddeleted-all-db", value);
    }

    @Test
    void stripsZeroWidthCharacters() {
      // Two names that render identically must not be able to differ invisibly.
      String disguised =
          "ad"
              + cp(ZERO_WIDTH_SPACE)
              + "m"
              + cp(ZERO_WIDTH_NON_JOINER)
              + "in"
              + cp(BYTE_ORDER_MARK);
      assertEquals("admin", ReportText.neutralize(disguised, 256).value());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00, 0x07, 0x1b, 0x7f, 0x85, 0x9f})
    void dropsControlCharacters(int control) {
      assertEquals("ab", ReportText.neutralize("a" + cp(control) + "b", 256).value());
    }

    @Test
    void collapsesWhitespaceRatherThanDroppingIt() {
      // A newline must not survive — it breaks out of its line in the report — but dropping it
      // outright would join two words into one and misrepresent the name.
      assertEquals("a b", ReportText.neutralize("a\nb", 256).value());
      assertEquals("a b", ReportText.neutralize("a\r\n\t  b", 256).value());
      assertEquals("a b", ReportText.neutralize("a" + cp(LINE_SEPARATOR) + "b", 256).value());
      assertEquals("a b", ReportText.neutralize("a" + cp(NO_BREAK_SPACE) + "b", 256).value());
    }

    @Test
    void trimsRatherThanEmittingEdgeWhitespace() {
      assertEquals("a", ReportText.neutralize("  \t a \n ", 256).value());
    }

    @Test
    void replacesUnpairedSurrogates() {
      // Not encodable as UTF-8; left alone it would corrupt or fail a downstream serializer.
      assertEquals(
          "a\ufffdb", ReportText.neutralize("a" + (char) HIGH_SURROGATE + "b", 256).value());
    }

    @Test
    void keepsWellFormedSupplementaryCharactersWhole() {
      String emoji = "a" + cp(GRINNING_FACE) + "b";
      assertEquals(emoji, ReportText.neutralize(emoji, 256).value());
    }

    @Test
    void truncatesDeterministicallyAndNeverExceedsTheBound() {
      ReportText.Text text = ReportText.neutralize("abcdefghij", 5);
      assertTrue(text.truncated());
      assertEquals(5, text.value().codePointCount(0, text.value().length()));
      assertTrue(text.value().endsWith(ReportText.TRUNCATION_MARKER));
      assertEquals(text.value(), ReportText.neutralize("abcdefghij", 5).value());
    }

    @Test
    void truncationNeverSplitsASupplementaryCharacter() {
      ReportText.Text text = ReportText.neutralize(cp(GRINNING_FACE).repeat(10), 4);
      assertTrue(text.truncated());
      // A lone surrogate at the cut would not survive a UTF-8 round trip intact.
      byte[] utf8 = text.value().getBytes(StandardCharsets.UTF_8);
      assertEquals(text.value(), new String(utf8, StandardCharsets.UTF_8));
    }

    @Test
    void anOversizedPayloadIsBoundedNotRejected() {
      // A megabyte URN is a denial-of-service attempt on whatever renders it. It must cost the
      // bound, not the input.
      ReportText.Text text = ReportText.neutralize("x".repeat(1_000_000), 64);
      assertTrue(text.truncated());
      assertEquals(64, text.value().codePointCount(0, text.value().length()));
    }

    @Test
    void aValueThatFitsIsNotMarkedTruncated() {
      assertFalse(ReportText.neutralize("abcde", 5).truncated());
      assertEquals("abcde", ReportText.neutralize("abcde", 5).value());
    }

    @Test
    void nullAndEmptyAndInvisibleOnlyAreAllEmpty() {
      assertEquals("", ReportText.neutralize(null, 16).value());
      assertEquals("", ReportText.neutralize("", 16).value());
      assertEquals("", ReportText.neutralize(cp(ZERO_WIDTH_SPACE) + cp(RTL_OVERRIDE), 16).value());
    }

    @Test
    void rejectsANonPositiveBound() {
      assertThrows(IllegalArgumentException.class, () -> ReportText.neutralize("a", 0));
    }
  }

  @Nested
  class MarkdownEmission {

    @Test
    void mentionsDoNotSurviveAsMentions() {
      // A URN naming a resource "@everyone" must not notify a whole organisation. A code span is
      // what prevents it, so the assertion is that the sigil is inside one.
      String urn = "urn:pulumi:prod::app::aws:s3/bucket:Bucket::@everyone";
      String emitted = Markdown.code(ReportText.neutralize(urn, 256).value());
      assertTrue(emitted.startsWith("`") && emitted.endsWith("`"));
      assertTrue(emitted.contains("@everyone"));
      assertEquals(0, outsideCodeSpans(emitted).indexOf('@') + 1, "an @ escaped the code span");
    }

    @Test
    void issueReferencesDoNotSurviveAsLinks() {
      String emitted = Markdown.code(ReportText.neutralize("fixes #1234", 256).value());
      assertFalse(outsideCodeSpans(emitted).contains("#"));
    }

    @Test
    void urlsFromStateDoNotSurviveAsLinks() {
      String emitted = Markdown.code(ReportText.neutralize("https://evil.invalid/x", 256).value());
      assertFalse(outsideCodeSpans(emitted).contains("://"));
    }

    @Test
    void htmlFromStateIsNotHtml() {
      String emitted = Markdown.code("<img src=x onerror=alert(1)>");
      assertFalse(outsideCodeSpans(emitted).contains("<"));
    }

    @Test
    void markdownFromStateIsNotMarkdown() {
      String emitted = Markdown.code("[click](https://evil.invalid) **bold**");
      assertFalse(outsideCodeSpans(emitted).contains("["));
      assertFalse(outsideCodeSpans(emitted).contains("*"));
    }

    @Test
    void contentCannotCloseItsOwnCodeSpan() {
      // The escape that matters: a fragment containing backticks must not be able to end the span
      // and continue as markdown.
      String emitted = Markdown.code("a`b``c @everyone");
      assertTrue(emitted.startsWith("```"), "fence must outrun the longest run inside: " + emitted);
      assertFalse(outsideCodeSpans(emitted).contains("@"));
    }

    @Test
    void contentThatIsOnlyBackticksIsStillContained() {
      String emitted = Markdown.code("```");
      assertFalse(outsideCodeSpans(emitted).contains("`"));
    }

    @Test
    void aPipeOutsideATableDoesNotGainAVisibleBackslash() {
      assertEquals("`a|b`", Markdown.code("a|b"));
    }

    @Test
    void aDiffBlockPredictsItsOwnSizeAsRowsLengthenTheFence() {
      // The plan is fitted to the body budget by bytesWith, so it must match the rendered block
      // exactly, including when a row's backticks force a longer fence on both lines.
      Markdown.DiffBlock block = new Markdown.DiffBlock();
      for (String row : new String[] {"+  a", "~  ``b", "±  ü ```` c", "-  d"}) {
        int predicted = block.bytesWith(row);
        block.add(row);
        assertEquals(predicted, block.render().getBytes(StandardCharsets.UTF_8).length, row);
      }
      assertTrue(block.render().startsWith("`````diff\n"), block.render());
    }

    @Test
    void emptyContentEmitsNothingRatherThanAnEmptySpan() {
      assertEquals("", Markdown.code(""));
      assertEquals("", Markdown.code(null));
    }
  }

  @Nested
  class UrlPolicy {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "javascript:alert(1)",
          "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
          "file:///etc/passwd",
          "/relative/path",
          "mailto:someone@example.invalid"
        })
    void refusesANonHttpTarget(String target) {
      assertFalse(Markdown.isEmittableLink(URI.create(target)));
      assertThrows(IllegalArgumentException.class, () -> Markdown.link("x", URI.create(target)));
    }

    @Test
    void refusesNull() {
      assertFalse(Markdown.isEmittableLink(null));
    }

    @Test
    void acceptsAnAbsoluteConsoleUrl() {
      URI console = URI.create("https://statedb.example.invalid/org/proj/stack/previews/abc");
      assertTrue(Markdown.isEmittableLink(console));
      assertEquals("[run](" + console + ")", Markdown.link("run", console));
    }

    @Test
    void percentEncodesSoATargetCannotEscapeTheLink() {
      URI console = URI.create("https://statedb.example.invalid/a%20b");
      String emitted = Markdown.link("run", console);
      assertEquals(1, emitted.chars().filter(c -> c == ')').count());
    }
  }

  @Nested
  class PropertyPaths {

    @Test
    void parsesTheFormsPulumiDocuments() throws Exception {
      JsonNode root =
          json(
              """
              {"root": {"nested": 1, "key with a .": 2, "array": [{"field": 3}]},
               "foo-bar": 4}
              """);
      assertEquals(1, PropertyPath.parse("root.nested").resolve(root).asInt());
      assertEquals(1, PropertyPath.parse("root[\"nested\"]").resolve(root).asInt());
      assertEquals(2, PropertyPath.parse("root[\"key with a .\"]").resolve(root).asInt());
      assertEquals(3, PropertyPath.parse("root.array[0].field").resolve(root).asInt());
      // Not the documented identifier production, but what providers actually emit.
      assertEquals(4, PropertyPath.parse("foo-bar").resolve(root).asInt());
    }

    @Test
    void toleratesADotBeforeABracketAsPulumiDoes() throws Exception {
      JsonNode root =
          json(
              """
          {"a": [7]}
          """);
      assertEquals(7, PropertyPath.parse("a.[0]").resolve(root).asInt());
    }

    @Test
    void aWildcardResolvesAsAMiss() throws Exception {
      // It names a set, and the caller wants one node; a miss routes it to the conservative path.
      JsonNode root =
          json(
              """
          {"a": [{"b": 1}]}
          """);
      assertNull(PropertyPath.parse("a[*].b").resolve(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {".leading", "a.", "a[", "a[\"unterminated", "a[nan]", "a[\"x\"", ""})
    void malformedPathsReturnNullRatherThanThrowing(String path) {
      assertNull(PropertyPath.parse(path));
    }

    @Test
    void aSegmentKindMismatchIsAMissNotACoercion() throws Exception {
      JsonNode root =
          json(
              """
          {"a": {"b": 1}}
          """);
      assertNull(PropertyPath.parse("a[0]").resolve(root));
      assertNull(PropertyPath.parse("a.b.c").resolve(root));
    }
  }

  /**
   * The text of {@code markdown} with every inline code span removed.
   *
   * <p>Assertions are written against this rather than against the raw string, because the claim
   * being made is "nothing dangerous is interpreted", and inside a code span nothing is. A test
   * that merely asserted the sigil is absent would fail on a correct implementation, which keeps
   * the sigil visible on purpose.
   */
  private static String outsideCodeSpans(String markdown) {
    StringBuilder outside = new StringBuilder();
    int i = 0;
    while (i < markdown.length()) {
      if (markdown.charAt(i) == '`') {
        int fenceEnd = i;
        while (fenceEnd < markdown.length() && markdown.charAt(fenceEnd) == '`') {
          fenceEnd++;
        }
        String fence = markdown.substring(i, fenceEnd);
        int close = markdown.indexOf(fence, fenceEnd);
        if (close < 0) {
          // An unclosed fence means the span never closed and the rest is ordinary text — which
          // is exactly the failure these assertions exist to catch.
          outside.append(markdown, fenceEnd, markdown.length());
          break;
        }
        i = close + fence.length();
      } else {
        outside.append(markdown.charAt(i));
        i++;
      }
    }
    return outside.toString();
  }

  private static boolean isBidi(int codePoint) {
    return IntStream.rangeClosed(0x202a, 0x202e).anyMatch(c -> c == codePoint)
        || IntStream.rangeClosed(0x2066, 0x2069).anyMatch(c -> c == codePoint)
        || codePoint == 0x200e
        || codePoint == 0x200f;
  }

  private static JsonNode json(String text) throws Exception {
    return MAPPER.readTree(text);
  }
}
