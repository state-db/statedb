package io.statedb.persistence.policy;

import java.util.Map;

public interface PolicyStore {

  String schema();

  String policies();

  /** Cedar policy templates by id (the id is the {@code TemplateLink} target). */
  Map<String, String> templates();
}
