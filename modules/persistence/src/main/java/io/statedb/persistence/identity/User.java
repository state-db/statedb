package io.statedb.persistence.identity;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(columnNames = {"iss", "sub"}))
public class User extends TimestampedEntity {

  @Column(nullable = false)
  public String iss;

  @Column(nullable = false)
  public String sub;

  public String email;

  @Column(name = "display_name")
  public String displayName;

  @Column(name = "last_login_at")
  public Instant lastLoginAt;
}
