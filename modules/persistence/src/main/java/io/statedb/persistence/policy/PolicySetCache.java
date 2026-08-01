package io.statedb.persistence.policy;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.LinkValue;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.policy.TemplateLink;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import io.statedb.persistence.authz.ResourceName;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Holds the compiled Cedar policy set. The set is assembled from three sources:
 *
 * <ul>
 *   <li>classpath <b>static policies</b> (the role policies),
 *   <li>classpath <b>templates</b> ({@code StackWriteAccess}, {@code Stack*ResourcePolicy}), and
 *   <li><b>template links</b> built from {@code policy_grants} rows — the per-principal "managed"
 *       grants and per-resource shares.
 * </ul>
 *
 * Built at startup and rebuilt by {@link #reload()} after a grant changes. A failed reload logs and
 * keeps the prior good set so an invalid grant cannot take authorization down.
 */
@Startup
@ApplicationScoped
public class PolicySetCache {

  private static final Logger LOG = Logger.getLogger(PolicySetCache.class);

  private static final EntityTypeName ACTION = type("Action");
  private static final EntityTypeName USER = type("User");
  private static final EntityTypeName ORG = type("Org");
  private static final EntityTypeName STACK = type("Stack");

  private static EntityTypeName type(String name) {
    return EntityTypeName.parse(name).orElseThrow(() -> new IllegalStateException(name + " type"));
  }

  @Inject PolicyStore policyStore;

  @Inject GrantLinkFinder grantLinkFinder;

  private volatile PolicySet policySet;

  // Action-group membership (e.g. stack:Create ∈ stack:WriteAccess) lives in the schema, but the
  // authorization engine never sees the schema — it resolves `action in Action::"…Access"` against
  // the entity slice. So we derive the action entities (with their memberOf parents) from the
  // schema once at startup and feed them into every request. Schema stays the single source of
  // truth.
  private Set<Entity> actionEntities;

  @PostConstruct
  void load() {
    try {
      Schema schema = new Schema(policyStore.schema());
      actionEntities = deriveActionEntities(schema);
      policySet = build(schema);
    } catch (Exception e) {
      throw new IllegalStateException("failed loading Cedar policies", e);
    }
  }

  /**
   * Rebuilds the policy set from the classpath sources and the current {@code policy_grants} rows.
   * On failure the previous good set is retained.
   */
  public synchronized void reload() {
    try {
      policySet = build(new Schema(policyStore.schema()));
    } catch (Exception e) {
      LOG.error("Cedar policy reload failed; keeping previous policy set", e);
    }
  }

  public PolicySet policySet() {
    return policySet;
  }

  public Set<Entity> actionEntities() {
    return actionEntities;
  }

  private PolicySet build(Schema schema) throws Exception {
    Set<Policy> statics = PolicySet.parsePolicies(policyStore.policies()).policies;
    Set<Policy> templates = new HashSet<>();
    for (Map.Entry<String, String> t : policyStore.templates().entrySet()) {
      templates.add(new Policy(t.getValue(), t.getKey()));
    }
    List<TemplateLink> links = QuarkusTransaction.requiringNew().call(this::loadLinks);

    PolicySet assembled = new PolicySet(statics, templates, links);
    ValidationResponse response =
        new BasicAuthorizationEngine().validate(new ValidationRequest(schema, assembled));
    if (!response.validationPassed()) {
      throw new IllegalStateException(response.toString());
    }
    assembled.cache();
    return assembled;
  }

  // One TemplateLink per policy_grants row: ?principal is always the user; ?resource is the stack
  // (org/project/stack) for a per-stack share, else the org for a managed (per-org) grant.
  private List<TemplateLink> loadLinks() {
    List<TemplateLink> links = new ArrayList<>();
    for (GrantLink g : grantLinkFinder.all()) {
      EntityUID resource =
          g.stackName() != null
              ? STACK.of(ResourceName.stack(g.stackOrg(), g.stackProject(), g.stackName()))
              : ORG.of(ResourceName.org(g.orgName()));
      List<LinkValue> values =
          List.of(
              new LinkValue("?principal", USER.of(g.principalUserId().toString())),
              new LinkValue("?resource", resource));
      links.add(new TemplateLink(g.templateId(), "link:" + g.id(), values));
    }
    return links;
  }

  private static Set<Entity> deriveActionEntities(Schema schema) throws Exception {
    Set<Entity> entities = new HashSet<>();
    JsonNode root = schema.toJsonFormat();
    for (Iterator<JsonNode> namespaces = root.elements(); namespaces.hasNext(); ) {
      JsonNode actions = namespaces.next().get("actions");
      if (actions == null) {
        continue;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = actions.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> action = it.next();
        Set<EntityUID> parents = new HashSet<>();
        JsonNode memberOf = action.getValue().get("memberOf");
        if (memberOf != null) {
          for (JsonNode parent : memberOf) {
            JsonNode id = parent.get("id");
            if (id == null || !id.isTextual()) {
              throw new IllegalStateException(
                  "action "
                      + action.getKey()
                      + " has invalid memberOf entry in Cedar schema: "
                      + parent);
            }
            parents.add(ACTION.of(id.asText()));
          }
        }
        entities.add(new Entity(ACTION.of(action.getKey()), parents));
      }
    }
    // Copied once here rather than on each actionEntities() call: this is an
    // application-scoped singleton on the authorization path, and every caller
    // only reads the set.
    return Set.copyOf(entities);
  }
}
