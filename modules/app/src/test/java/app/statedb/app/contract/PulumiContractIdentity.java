package app.statedb.app.contract;

import static app.statedb.testsupport.TestIdentity.sha256Hex;
import static app.statedb.testsupport.TestTransactions.tx;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.StackCreator;
import java.time.Instant;
import java.util.Map;

public record PulumiContractIdentity(
    String organizationName,
    String organizationDisplayName,
    String subject,
    String email,
    String userDisplayName,
    String rawToken,
    String tokenDescription) {
  private static final String ISSUER = "local:test";

  public void seed() {
    tx(this::persist);
  }

  public void seedStack(StackCreator stackCreator, String project, String stack) {
    tx(() -> stackCreator.create(persist().id, organizationName, project, stack, Map.of()));
  }

  private Organization persist() {
    Organization organization = new Organization();
    organization.name = organizationName;
    organization.displayName = organizationDisplayName;
    organization.persistAndFlush();

    persistOwner(organization);
    return organization;
  }

  private void persistOwner(Organization organization) {
    User user = new User();
    user.iss = ISSUER;
    user.sub = subject;
    user.email = email;
    user.displayName = userDisplayName;
    user.persistAndFlush();

    OrgMember member = new OrgMember();
    member.orgId = organization.id;
    member.userId = user.id;
    member.role = OrgMember.Role.owner;
    member.persistAndFlush();

    ApiToken token = new ApiToken();
    token.tokenHash = sha256Hex(rawToken);
    token.userId = user.id;
    token.orgId = organization.id;
    token.kind = ApiToken.Kind.service_account;
    token.expiresAt = Instant.now().plusSeconds(3600);
    token.description = tokenDescription;
    token.persistAndFlush();
  }

  public void cleanup() {
    tx(
        () -> {
          Organization.delete("name", organizationName);
          User.delete("iss = ?1 and sub = ?2", ISSUER, subject);
        });
  }
}
