package app.statedb.persistence;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.identity.OrgMembershipView;
import app.statedb.persistence.identity.OrganizationAggregateCount;
import app.statedb.persistence.identity.ServiceAccountView;
import app.statedb.persistence.identity.UserOrgMembershipView;
import app.statedb.persistence.operations.ActiveLockView;
import app.statedb.persistence.operations.CompactionJobView;
import app.statedb.persistence.policy.GrantLink;
import app.statedb.persistence.policy.MemberStackGrantView;
import app.statedb.persistence.stack.SnapshotResourceCount;
import app.statedb.persistence.stack.SnapshotRetentionRow;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.LineageSnapshotVersion;
import app.statedb.persistence.update.UpdateJournalBoundary;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native Image registration for constructor-projection targets.
 *
 * <p>Quarkus registers entities and REST payload types from the Jandex index, but a projection
 * target is named only inside an HQL string or passed to {@code .project(Foo.class)}, so build-time
 * augmentation cannot see it. Without registration the native binary compiles and then fails at
 * runtime the first time the query runs. Every record here is instantiated reflectively by
 * Hibernate; add a new one alongside its finder.
 */
@RegisterForReflection(
    targets = {
      ActiveLockView.class,
      AuthenticatedPrincipal.class,
      CompactionJobView.class,
      GrantLink.class,
      LineageSnapshotVersion.class,
      MemberStackGrantView.class,
      OrgMembershipView.class,
      OrganizationAggregateCount.class,
      ServiceAccountView.class,
      SnapshotResourceCount.class,
      SnapshotRetentionRow.class,
      StackRef.class,
      UpdateJournalBoundary.class,
      UserOrgMembershipView.class
    })
public final class ProjectionReflection {

  private ProjectionReflection() {}
}
