package app.statedb.persistence.identity;

import app.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "oidc_providers")
public class OidcProvider extends TimestampedEntity {

  /** Immutable connection settings consumed by Quarkus's dynamic OIDC tenant resolver. */
  public record ConnectionConfig(
      String name, String issuerUrl, String clientId, String clientSecret, List<String> scopes) {}

  public static Optional<ConnectionConfig> findEnabled(String name) {
    return OidcProvider.<OidcProvider>find("name = ?1 and enabled = true", name)
        .firstResultOptional()
        .map(
            provider ->
                new ConnectionConfig(
                    provider.name,
                    provider.issuerUrl,
                    provider.clientId,
                    provider.clientSecret,
                    provider.scopes));
  }

  @Column(nullable = false, unique = true)
  public String name;

  @Column(name = "issuer_url", nullable = false)
  public String issuerUrl;

  @Column(name = "client_id", nullable = false)
  public String clientId;

  @Column(name = "client_secret", nullable = false)
  public String clientSecret;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  public List<String> scopes;

  @Column(nullable = false)
  public boolean enabled = true;
}
