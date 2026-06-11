package io.statedb.persistence.policy;

import io.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

  @Column(name = "template_id", nullable = false)
  public String templateId;

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Column(name = "principal_user_id", nullable = false)
  public UUID principalUserId;

  @Column(name = "resource_stack_id")
  public UUID resourceStackId;
}
