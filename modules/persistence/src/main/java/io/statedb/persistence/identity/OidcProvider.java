package io.statedb.persistence.identity;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "oidc_providers")
public class OidcProvider extends TimestampedEntity {

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
