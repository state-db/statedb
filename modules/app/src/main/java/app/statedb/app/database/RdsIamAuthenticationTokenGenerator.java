package app.statedb.app.database;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.function.Supplier;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/** Generates SigV4 PostgreSQL passwords from the ECS task role's container credentials. */
@ApplicationScoped
public class RdsIamAuthenticationTokenGenerator {

  private static final String RDS_SIGNING_NAME = "rds-db";
  private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

  private final Supplier<AwsCredentialsIdentity> credentials;
  private final AwsV4HttpSigner signer;

  @Inject
  RdsIamAuthenticationTokenGenerator(EcsTaskCredentialsProvider credentialsProvider) {
    this(credentialsProvider::resolveCredentials);
  }

  RdsIamAuthenticationTokenGenerator(Supplier<AwsCredentialsIdentity> credentials) {
    this.credentials = credentials;
    this.signer = AwsV4HttpSigner.create();
  }

  String generate(String host, int port, String username, String region) {
    SdkHttpRequest request =
        SdkHttpRequest.builder()
            .protocol("https")
            .host(host)
            .port(port)
            .method(SdkHttpMethod.GET)
            .putRawQueryParameter("Action", "connect")
            .putRawQueryParameter("DBUser", username)
            .build();
    String signedUri =
        signer
            .sign(
                signRequest ->
                    signRequest
                        .identity(credentials.get())
                        .request(request)
                        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, RDS_SIGNING_NAME)
                        .putProperty(
                            AwsV4FamilyHttpSigner.AUTH_LOCATION,
                            AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                        .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, TOKEN_LIFETIME))
            .request()
            .getUri()
            .toString();
    return signedUri.substring("https://".length());
  }
}
