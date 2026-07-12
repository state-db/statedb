package io.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Revokes a service account by deleting its machine {@code users} row — the V001 {@code ON DELETE
 * CASCADE} FKs take the membership and token with it, so the token stops authenticating in the same
 * transaction.
 */
@ApplicationScoped
public class ServiceAccountDeleter {

  @Inject ServiceAccountFinder finder;

  /**
   * @return false when the account doesn't exist as a service identity in this org — tenant
   *     isolation: an id from another org is indistinguishable from an unknown one
   */
  @Transactional
  public boolean delete(UUID orgId, UUID accountId) {
    if (!finder.existsInOrg(orgId, accountId)) {
      return false;
    }
    // The org check above plus the reserved issuer keep this from ever deleting a human user.
    return User.delete("id = ?1 and iss = ?2", accountId, ServiceAccountCreator.SERVICE_ISS) > 0;
  }
}
