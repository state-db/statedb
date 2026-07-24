package io.statedb.pulumiapi.unsupported;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Builds the response the out-of-scope surface stubs in this package share: a Pulumi-style error
 * body ({@code {"code":404,"message":"…"}}), which the CLI renders as {@code [404] <message>}.
 *
 * <p>404 rather than the semantically nicer 501, because the CLI retries every 5xx: {@code
 * httputil.doWithRetry} only accepts a response when the status is outside 500-599, and {@code
 * defaultHTTPClient.Do} configures {@code MaxRetryCount: 4} with a 1s delay and 2x backoff. A 501
 * therefore costs four round trips and ~7s before the user sees the message. Anything under 500 is
 * returned on the first attempt, and {@code pulumiAPICall} decodes 4xx and 5xx bodies identically,
 * so the message survives. A bare 404 from an unmapped path is what we are avoiding — that decodes
 * to an empty message and the CLI prints {@code [404]} with nothing after it.
 *
 * <p>Pulumi source references (checkout at v3.242.0): {@code
 * sdk/go/common/util/httputil/http.go:92}, {@code pkg/backend/httpstate/client/api.go:366} and
 * {@code :499}.
 */
public final class UnsupportedFeature {

  private UnsupportedFeature() {}

  /** {@code feature} is a plural noun phrase — it is completed with "are not supported…". */
  public static Response notSupported(String feature) {
    return Response.status(Response.Status.NOT_FOUND)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 404, "message", feature + " are not supported by this backend"))
        .build();
  }
}
