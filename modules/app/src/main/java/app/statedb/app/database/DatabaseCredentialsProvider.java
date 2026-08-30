package app.statedb.app.database;

import io.quarkus.arc.Unremovable;
import io.quarkus.credentials.CredentialsProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Supplies either a configured password or a fresh RDS IAM token to each new JDBC connection. */
@ApplicationScoped
@Unremovable
@Named("statedb-db-credentials-provider")
public class DatabaseCredentialsProvider implements CredentialsProvider {

  static final String PROVIDER_KEY = "statedb";
  static final String PASSWORD_MODE = "password";
  static final String AWS_IAM_MODE = "aws-iam";

  private final RdsIamAuthenticationTokenGenerator tokenGenerator;
  private final String authentication;
  private final Optional<String> username;
  private final Optional<String> password;
  private final Optional<String> host;
  private final int port;
  private final Optional<String> region;

  @Inject
  DatabaseCredentialsProvider(
      RdsIamAuthenticationTokenGenerator tokenGenerator,
      @ConfigProperty(name = "statedb.db.authentication", defaultValue = PASSWORD_MODE)
          String authentication,
      @ConfigProperty(name = "statedb.db.user") Optional<String> username,
      @ConfigProperty(name = "statedb.db.password") Optional<String> password,
      @ConfigProperty(name = "statedb.db.host") Optional<String> host,
      @ConfigProperty(name = "statedb.db.port", defaultValue = "5432") int port,
      @ConfigProperty(name = "statedb.db.region") Optional<String> region) {
    this.tokenGenerator = tokenGenerator;
    this.authentication = authentication.toLowerCase(Locale.ROOT);
    this.username = username.filter(value -> !value.isBlank());
    this.password = password.filter(value -> !value.isBlank());
    this.host = host.filter(value -> !value.isBlank());
    this.port = port;
    this.region = region.filter(value -> !value.isBlank());
  }

  @Override
  public Map<String, String> getCredentials(String credentialsProviderName) {
    if (!PROVIDER_KEY.equals(credentialsProviderName)) {
      throw new IllegalArgumentException(
          "unknown database credentials provider key: " + credentialsProviderName);
    }
    String configuredUsername = username.orElseThrow(() -> missingConfiguration("STATEDB_DB_USER"));
    return switch (authentication) {
      case PASSWORD_MODE ->
          Map.of(
              USER_PROPERTY_NAME,
              configuredUsername,
              PASSWORD_PROPERTY_NAME,
              password.orElseThrow(() -> missingConfiguration("STATEDB_DB_PASSWORD")));
      case AWS_IAM_MODE ->
          Map.of(
              USER_PROPERTY_NAME,
              configuredUsername,
              PASSWORD_PROPERTY_NAME,
              tokenGenerator.generate(
                  host.orElseThrow(() -> missingConfiguration("STATEDB_DB_HOST")),
                  port,
                  configuredUsername,
                  region.orElseThrow(() -> missingConfiguration("STATEDB_DB_REGION"))));
      default ->
          throw new IllegalStateException(
              "STATEDB_DB_AUTHENTICATION must be 'password' or 'aws-iam'");
    };
  }

  private static IllegalStateException missingConfiguration(String environmentVariable) {
    return new IllegalStateException(environmentVariable + " is required for database access");
  }
}
