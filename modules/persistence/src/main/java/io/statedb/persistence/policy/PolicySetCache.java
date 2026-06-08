package io.statedb.persistence.policy;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class PolicySetCache {

  @Inject PolicyStore policyStore;

  private PolicySet policySet;

  @PostConstruct
  void load() {
    try {
      policySet = PolicySet.parsePolicies(policyStore.policies());
      Schema schema = new Schema(policyStore.schema());
      ValidationResponse response =
          new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policySet));
      if (!response.validationPassed()) {
        throw new IllegalStateException(response.toString());
      }
      policySet.cache();
    } catch (Exception e) {
      throw new IllegalStateException("failed loading Cedar policies", e);
    }
  }

  public PolicySet policySet() {
    return policySet;
  }
}
