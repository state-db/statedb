package io.statedb.persistence.identity;

import io.statedb.persistence.auth.ApiToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Soft-revokes a service account and its sole credential. The identity and membership remain for
 * historical attribution; authentication filters both revocation and account deactivation.
 */
@ApplicationScoped
public class ServiceAccountRevoker {

  @Inject ServiceAccountFinder finder;

  /**
   * @return false when the account doesn't exist as a service identity in this org — tenant
   *     isolation: an id from another org is indistinguishable from an unknown one
   */
  @Transactional
  public boolean revoke(UUID orgId, UUID accountId, UUID revokedByUserId) {
    if (!finder.existsInOrg(orgId, accountId)) {
      return false;
    }
    Instant now = Instant.now();
    ApiToken.update(
        "revokedAt = coalesce(revokedAt, ?1), revokedByUserId = coalesce(revokedByUserId, ?2)"
            + " where userId = ?3 and orgId = ?4 and kind = ?5",
        now,
        revokedByUserId,
        accountId,
        orgId,
        ApiToken.Kind.service_account);
    User.update(
        "deactivatedAt = coalesce(deactivatedAt, ?1),"
            + " deactivatedByUserId = coalesce(deactivatedByUserId, ?2)"
            + " where id = ?3 and iss = ?4",
        now,
        revokedByUserId,
        accountId,
        ServiceAccountCreator.SERVICE_ISS);
    return true;
  }
}
