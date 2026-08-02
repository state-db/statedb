package app.statedb.persistence.policy;

import app.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;

/**
 * One attachment of a Cedar policy template to a principal (and optionally a stack). Rows are read
 * at startup and on {@link PolicySetCache#reload()} and turned into Cedar {@code TemplateLink}s —
 * see {@link PolicySetCache} and {@link GrantLinkFinder}. Append-only: revoking a grant deletes its
 * row; leaving an org / deleting a stack or user cascades the row away.
 *
 * <ul>
 *   <li>{@code resourceStackId == null} → a managed (per-org) grant; {@code ?resource} is the org.
 *   <li>{@code resourceStackId != null} → a per-stack share; {@code ?resource} is that stack.
 * </ul>
 */
@Entity
@Table(name = "policy_grants")
public class PolicyGrant extends BaseEntity {

  /**
   * The per-stack share templates whose grants include {@code stack:ReadOnlyAccess}. This is the
   * read-granting set the list finders' SQL predicate and {@link
   * app.statedb.persistence.authz.StackEntityLoader}'s 404 gate mirror — it must stay in lockstep
   * with the templates' Cedar text (see design/stack-sharing.md §9).
   */
  public static final List<String> STACK_READ_TEMPLATE_IDS =
      List.of("StackReadResourcePolicy", "StackWriteResourcePolicy");

  /** Whether the user holds a read-granting per-stack share on the given stack. */
  public static boolean hasStackReadShare(UUID userId, UUID stackId) {
    return count(
            "principalUserId = ?1 and resourceStackId = ?2 and templateId in ?3",
            userId,
            stackId,
            STACK_READ_TEMPLATE_IDS)
        > 0;
  }

  @Column(name = "template_id", nullable = false)
  public String templateId;

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Column(name = "principal_user_id", nullable = false)
  public UUID principalUserId;

  @Column(name = "resource_stack_id")
  public UUID resourceStackId;
}
