package app.statedb.vcs.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Signs short-lived GitHub App JWTs from the runtime-stored App identity. */
final class GitHubAppJwt {

  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private GitHubAppJwt() {}

  static String sign(long appId, String privateKeyPem, Instant now) {
    String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
    String payload =
        encode(
            "{\"iat\":"
                + now.minusSeconds(60).getEpochSecond()
                + ",\"exp\":"
                + now.plusSeconds(9 * 60).getEpochSecond()
                + ",\"iss\":\""
                + appId
                + "\"}");
    String signingInput = header + "." + payload;
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(parseKey(privateKeyPem));
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + BASE64_URL.encodeToString(signer.sign());
    } catch (Exception failure) {
      throw new IllegalStateException("Could not sign GitHub App JWT", failure);
    }
  }

  private static String encode(String value) {
    return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static PrivateKey parseKey(String pem) {
    boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(encoded);
      if (pkcs1) {
        der = wrapPkcs1(der);
      }
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception failure) {
      throw new IllegalArgumentException("GitHub App private key is not a valid RSA PEM", failure);
    }
  }

  private static byte[] wrapPkcs1(byte[] key) {
    byte[] algorithm =
        new byte[] {
          0x30,
          0x0d,
          0x06,
          0x09,
          0x2a,
          (byte) 0x86,
          0x48,
          (byte) 0x86,
          (byte) 0xf7,
          0x0d,
          0x01,
          0x01,
          0x01,
          0x05,
          0x00
        };
    byte[] version = new byte[] {0x02, 0x01, 0x00};
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(version);
    body.writeBytes(algorithm);
    body.writeBytes(der((byte) 0x04, key));
    return der((byte) 0x30, body.toByteArray());
  }

  private static byte[] der(byte tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int length = value.length;
    if (length < 128) {
      out.write(length);
    } else {
      int bytes = 0;
      for (int remaining = length; remaining > 0; remaining >>>= 8) {
        bytes++;
      }
      out.write(0x80 | bytes);
      for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
        out.write((length >>> shift) & 0xff);
      }
    }
    out.writeBytes(value);
    return out.toByteArray();
  }
}
