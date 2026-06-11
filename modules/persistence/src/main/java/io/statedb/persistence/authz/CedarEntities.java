package io.statedb.persistence.authz;

import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import java.util.UUID;

final class CedarEntities {

  private CedarEntities() {}

  static EntityUID euid(String type, String id) {
    EntityTypeName typeName =
        EntityTypeName.parse(type).orElseThrow(() -> new IllegalArgumentException(type));
    return typeName.of(id);
  }

  static EntityUID user(UUID userId) {
    return euid("User", userId.toString());
  }

  static EntityUID org(String orgName) {
    return euid("Org", orgName);
  }

  static EntityUID project(String orgName, String projectName) {
    return euid("Project", orgName + "/" + projectName);
  }

  static EntityUID stack(String orgName, String projectName, String stackName) {
    return euid("Stack", orgName + "/" + projectName + "/" + stackName);
  }

  static EntityUID role(String role) {
    return euid("Role", role);
  }
}
