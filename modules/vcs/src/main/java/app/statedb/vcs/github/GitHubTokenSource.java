package app.statedb.vcs.github;

import app.statedb.persistence.vcs.ReportPublisher.Repository;

/** Supplies a short-lived credential for one repository publication. */
public interface GitHubTokenSource {

  String token(Repository repository);

  /** GraphQL Bot.login is the App slug; REST appends [bot] to its user login. */
  String graphqlBotLogin();
}
