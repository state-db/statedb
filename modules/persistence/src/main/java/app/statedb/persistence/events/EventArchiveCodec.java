package app.statedb.persistence.events;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes an update's event stream to the compressed form stored in {@link UpdateEventArchive}
 * and back.
 *
 * <p>The wire form is newline-delimited JSON, one object per event carrying exactly the fields
 * {@link StoredEvent} exposes, zstd-compressed as a whole. Block compression over the concatenated
 * stream is the entire point: consecutive resource events repeat the same URNs, provider
 * references, and property bags, and that redundancy is invisible to Postgres' per-datum TOAST
 * compression.
 *
 * <p>Round-tripping is lossless for what readers observe. {@code payload} is written verbatim from
 * the {@code JsonNode} loaded out of {@code jsonb} and re-parsed on the way back, so the JSON a
 * reader serializes is byte-identical either way — jsonb's own normalization (key ordering,
 * duplicate-key collapse) has already happened at write time and is preserved, not re-applied.
 *
 * <p>zstd rather than the JDK's gzip, which costs a dependency and is worth it. Measured over ten
 * consecutive applied updates from a soak stack (36,375,188 bytes of event rows): per-update {@code
 * gzip -9} produced 700,313 bytes, per-update {@code zstd -19} produced 394,225 — 43.7% smaller for
 * the same lossless content. Training a shared dictionary on the same corpus reached 372,407, a
 * further 3% for a 112 kB per-stack artifact plus a retraining lifecycle, which is not worth it.
 *
 * <p>Compressing the ten updates as one concatenated stream came to 701,367 bytes — 0.2%
 * <em>worse</em> than compressing them separately — because a sliding window cannot reach across an
 * update. Per-update framing therefore costs nothing, and the remaining headroom is in the codec,
 * not the batching.
 *
 * <p>The codec name is stored per row so a future codec can be introduced without rewriting
 * existing archives.
 */
@ApplicationScoped
public class EventArchiveCodec {

  /** Codec tag for zstd over newline-delimited JSON. */
  public static final String ZSTD = "zstd";

  /**
   * Compression level. This runs on a background worker and the blob is written once and read many
   * times, so the slowest level is free.
   */
  private static final int LEVEL = 19;

  @Inject ObjectMapper mapper;

  public Encoder encoder() {
    return new Encoder(mapper);
  }

  /**
   * Decodes at most {@code limit} events with {@code sequence > after}, without materializing the
   * ones it skips over.
   *
   * <p>This is what keeps paging affordable. The console walks a run's whole stream one page at a
   * time, so a page-sized read that decoded the entire blob would rebuild every event's tree on
   * every request — quadratic, and measurably so: a full walk of a 50,000-event archive cost 12.1s
   * that way versus 1.1s from rows. Skipping a prefix costs decompression plus JSON tokenization
   * and no object allocation, and the parser stops reading — so stops inflating — as soon as the
   * page is full; the same walk then costs 3.2s. Re-inflating the prefix keeps that superlinear, so
   * a run large enough for it to matter again wants a chunked blob with a sequence-to-offset index,
   * not a bigger decode.
   *
   * <p>The fast path depends on the encoder writing {@code sequence} as each object's first field.
   * If it ever isn't, an event is materialized rather than skipped: slower, never wrong.
   */
  public List<StoredEvent> decodePage(String codec, byte[] blob, int after, int limit) {
    requireKnown(codec);
    List<StoredEvent> page = new ArrayList<>(Math.min(limit, 512));
    try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(blob));
        JsonParser parser = mapper.getFactory().createParser(in)) {
      while (page.size() < limit && parser.nextToken() == JsonToken.START_OBJECT) {
        int sequence = -1;
        String kind = null;
        String severity = null;
        JsonNode payload = null;
        boolean skip = false;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          String field = parser.currentName();
          parser.nextToken();
          switch (field) {
            case "sequence" -> {
              sequence = parser.getIntValue();
              skip = sequence <= after;
            }
            case "kind" -> kind = parser.getText();
            case "severity" ->
                severity = parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getText();
            case "payload" -> {
              if (skip) {
                parser.skipChildren();
              } else {
                payload = mapper.readTree(parser);
              }
            }
            default -> parser.skipChildren();
          }
        }
        if (!skip) {
          page.add(new StoredEvent(sequence, kind, severity, payload));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to decode event archive", e);
    }
    return page;
  }

  /** The whole stream. Used by unfold, which needs every event to rebuild the rows. */
  public List<StoredEvent> decode(String codec, byte[] blob) {
    requireKnown(codec);
    List<StoredEvent> events = new ArrayList<>();
    try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(blob));
        MappingIterator<JsonNode> it = mapper.readerFor(JsonNode.class).readValues(in)) {
      while (it.hasNext()) {
        JsonNode node = it.next();
        JsonNode severity = node.get("severity");
        events.add(
            new StoredEvent(
                node.required("sequence").asInt(),
                node.required("kind").asText(),
                severity == null || severity.isNull() ? null : severity.asText(),
                node.required("payload")));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to decode event archive", e);
    }
    return events;
  }

  private static void requireKnown(String codec) {
    if (!ZSTD.equals(codec)) {
      throw new IllegalStateException("unknown event archive codec: " + codec);
    }
  }

  /**
   * Accumulates events into the compressed form without ever holding the whole stream in memory as
   * objects, so the folder can page rows in and let each batch go.
   */
  public static final class Encoder {

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final JsonGenerator generator;
    private int count;
    private int maxSequence = -1;

    private Encoder(ObjectMapper mapper) {
      try {
        ZstdOutputStream zstd = new ZstdOutputStream(sink, LEVEL);
        this.generator = mapper.getFactory().createGenerator(zstd);
        this.generator.setPrettyPrinter(new NewlineSeparator());
      } catch (IOException e) {
        throw new UncheckedIOException("failed to open event archive encoder", e);
      }
    }

    public void add(StoredEvent event) {
      try {
        generator.writeStartObject();
        generator.writeNumberField("sequence", event.sequence());
        generator.writeStringField("kind", event.kind());
        if (event.severity() != null) {
          generator.writeStringField("severity", event.severity());
        }
        generator.writeFieldName("payload");
        generator.writeTree(event.payload());
        generator.writeEndObject();
      } catch (IOException e) {
        throw new UncheckedIOException("failed to encode event " + event.sequence(), e);
      }
      count++;
      maxSequence = Math.max(maxSequence, event.sequence());
    }

    public int count() {
      return count;
    }

    public int maxSequence() {
      return maxSequence;
    }

    /** Closes the stream and returns the blob. The encoder is not reusable afterwards. */
    public byte[] finish() {
      try {
        generator.close();
      } catch (IOException e) {
        throw new UncheckedIOException("failed to finish event archive", e);
      }
      return sink.toByteArray();
    }
  }

  /**
   * Puts a newline between root-level objects and nothing else, giving newline-delimited JSON. The
   * separator is for readability when inspecting a decompressed archive by hand; the decoder reads
   * concatenated values and does not depend on it.
   */
  private static final class NewlineSeparator
      extends com.fasterxml.jackson.core.util.MinimalPrettyPrinter {
    @Override
    public void writeRootValueSeparator(JsonGenerator generator) throws IOException {
      generator.writeRaw('\n');
    }
  }
}
