package app.statedb.persistence.authz;

/**
 * Builds the fully-qualified resource names that identify state-db resources in Cedar. The shape is
 * IAM-inspired but state-db-native (no Amazon ARN branding): {@code statedb:<service>:<path>},
 * where the service is the resource type and the path is {@code org[/project[/stack]]}. So a stack
 * reads {@code statedb:stack:acme/web/prod}.
 *
 * <p>The resource name is both the Cedar entity id <i>and</i> an {@code id} attribute on the
 * entity, so policies can pattern-match it the IAM way — e.g. a {@code resource.id like} pattern
 * that ends in a stack name, the way an IAM policy globs a resource.
 */
public final class ResourceName {

  private ResourceName() {}

  public static String site() {
    return "statedb:site:root";
  }

  public static String org(String org) {
    return "statedb:org:" + org;
  }

  public static String project(String org, String project) {
    return "statedb:project:" + org + "/" + project;
  }

  public static String stack(String org, String project, String stack) {
    return "statedb:stack:" + org + "/" + project + "/" + stack;
  }
}
