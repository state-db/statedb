package app.statedb.pulumiapi.secrets;

import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.secrets.ServiceSecretsAead;
import app.statedb.persistence.stack.StackRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.crypto.tink.Aead;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements Pulumi's service secrets provider for a stack. The CLI's {@code serviceCrypter} calls
 * these endpoints when the stack's {@code secretsProvider.type} is {@code "service"}.
 *
 * <p>Cross-stack isolation is enforced by passing the stack's UUIDv7 (16 bytes) as the associated
 * data (AAD) on every {@link Aead#encrypt} / {@link Aead#decrypt} call. AES-GCM authenticates the
 * AAD, so a ciphertext produced under stack A's AAD fails the tag check when decrypted under stack
 * B's AAD. Binding to {@code stack.id} (not the wire path) survives rename and forbids reuse on
 * delete-and-recreate. See {@code design/secrets.md}.
 */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class SecretsResource {

  @Inject AuthorizedResource authorized;

  @Inject ServiceSecretsAead secretsAead;

  @POST
  @Path("encrypt")
  @Authorize(action = "stack:EncryptSecrets", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public EncryptResponse encrypt(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      EncryptRequest request) {
    byte[] aad = aad();
    if (request == null || request.plaintext() == null) {
      throw badRequest("plaintext is required");
    }
    try {
      byte[] ciphertext =
          secretsAead.aead().encrypt(request.plaintext().getBytes(StandardCharsets.UTF_8), aad);
      return new EncryptResponse(Base64.getEncoder().encodeToString(ciphertext));
    } catch (GeneralSecurityException e) {
      // Encryption with a valid Aead should not fail; surface as 500.
      throw new WebApplicationException("encryption failed", 500);
    }
  }

  @POST
  @Path("decrypt")
  @Authorize(action = "stack:DecryptSecrets", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public DecryptResponse decrypt(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      DecryptRequest request) {
    byte[] aad = aad();
    if (request == null || request.ciphertext() == null) {
      throw badRequest("ciphertext is required");
    }
    String plaintext = decryptOne(request.ciphertext(), aad);
    return new DecryptResponse(plaintext);
  }

  @POST
  @Path("batch-decrypt")
  @Authorize(action = "stack:DecryptSecrets", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public BatchDecryptResponse batchDecrypt(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      BatchDecryptRequest request) {
    byte[] aad = aad();
    List<String> ciphertexts = request == null ? null : request.ciphertexts();
    if (ciphertexts == null) {
      throw badRequest("ciphertexts is required");
    }
    // LinkedHashMap preserves insertion order; identical inputs collapse to one entry.
    Map<String, String> out = new LinkedHashMap<>();
    for (String ct : ciphertexts) {
      if (ct == null) {
        throw badRequest("invalid ciphertext");
      }
      if (out.containsKey(ct)) {
        continue;
      }
      out.put(ct, decryptOne(ct, aad));
    }
    return new BatchDecryptResponse(out);
  }

  private String decryptOne(String b64, byte[] aad) {
    byte[] ciphertext;
    try {
      ciphertext = Base64.getDecoder().decode(b64);
    } catch (IllegalArgumentException e) {
      throw badRequest("invalid ciphertext");
    }
    try {
      byte[] plaintext = secretsAead.aead().decrypt(ciphertext, aad);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // Generic 400 regardless of which check failed (truncation, tag, unknown key id, AAD).
      throw badRequest("invalid ciphertext");
    }
  }

  // The @Authorize(StackEntityLoader) interceptor resolved this exact org/project/stack and
  // enforced the org boundary before the handler runs; reuse its StackRef for the AAD rather than
  // re-comparing against the token's org — which would be wrong for account-scoped tokens whose
  // orgId is null.
  private byte[] aad() {
    return uuidBytes(authorized.subject(StackRef.class).stackId());
  }

  private static byte[] uuidBytes(UUID id) {
    ByteBuffer buf = ByteBuffer.allocate(16);
    buf.putLong(id.getMostSignificantBits());
    buf.putLong(id.getLeastSignificantBits());
    return buf.array();
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("code", 400, "message", message))
            .build());
  }

  public record EncryptRequest(@JsonProperty("plaintext") String plaintext) {}

  public record EncryptResponse(@JsonProperty("ciphertext") String ciphertext) {}

  public record DecryptRequest(@JsonProperty("ciphertext") String ciphertext) {}

  public record DecryptResponse(@JsonProperty("plaintext") String plaintext) {}

  public record BatchDecryptRequest(@JsonProperty("ciphertexts") List<String> ciphertexts) {}

  public record BatchDecryptResponse(@JsonProperty("plaintexts") Map<String, String> plaintexts) {}
}
