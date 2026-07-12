package io.statedb.persistence.auth;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The single definition of the raw API token wire shape, shared by every mint path ({@link
 * CliTokenMinter}, {@code ServiceAccountCreator}). Pulumi-style opaque token: a cosmetic {@code
 * pul-} prefix (aids log/grep identification) over 32 bytes of {@link SecureRandom}, base64url. The
 * raw value is returned once and never stored — only its {@link ApiTokenHasher} digest lands in
 * {@code api_tokens.token_hash}.
 */
public final class RawApiTokens {

  private static final String TOKEN_PREFIX = "pul-";

  private static final int RAW_TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private RawApiTokens() {}

  public static String generate() {
    byte[] bytes = new byte[RAW_TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
