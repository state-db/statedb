package app.statedb.testsupport;

import app.statedb.persistence.vcs.ReportPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-process host fake. Real marker and minimization behavior is tested through HTTP separately.
 */
@ApplicationScoped
public class InMemoryVcs implements ReportPublisher {

  private PullRequestTarget target;
  private final List<Comment> comments = new ArrayList<>();
  private boolean failAfterPost;
  private boolean forbidden;

  public synchronized void reset() {
    target = null;
    comments.clear();
    failAfterPost = false;
    forbidden = false;
  }

  @Override
  public boolean supports(String kind, String host) {
    return "memory".equals(kind);
  }

  public synchronized void addPullRequest(
      String repositoryId, long number, String head, boolean open) {
    target = open ? new PullRequestTarget(repositoryId, number, head) : null;
  }

  @Override
  public synchronized Optional<PullRequestTarget> resolve(
      Repository repository, Long prNumber, String commit) {
    return Optional.ofNullable(target)
        .filter(t -> t.headSha().equals(commit) && (prNumber == null || prNumber == t.number()));
  }

  @Override
  public synchronized String publishComment(
      Repository repository,
      PullRequestTarget pr,
      UUID stack,
      UUID update,
      long generation,
      String body) {
    if (forbidden) throw new Failure("permission denied", false, 0);
    Comment current =
        comments.stream().filter(c -> c.updateId.equals(update)).findFirst().orElse(null);
    if (current == null) {
      current = new Comment("comment-" + (comments.size() + 1), stack, update, generation, body);
      comments.add(current);
    }
    if (failAfterPost) {
      failAfterPost = false;
      throw new Failure("connection lost after POST", true, 0);
    }
    Comment newest =
        comments.stream()
            .filter(c -> c.stackId.equals(stack))
            .max(Comparator.comparingLong(c -> c.generation))
            .orElseThrow();
    comments.stream()
        .filter(c -> c.stackId.equals(stack) && c != newest)
        .forEach(c -> c.minimized = true);
    return current.id;
  }

  public synchronized void failAfterNextPost() {
    failAfterPost = true;
  }

  public synchronized void forbidPublication() {
    forbidden = true;
  }

  public synchronized List<Comment> comments() {
    return List.copyOf(comments);
  }

  public static final class Comment {
    public final String id;
    public final UUID stackId;
    public final UUID updateId;
    public final long generation;
    public final String body;
    public boolean minimized;

    Comment(String id, UUID stackId, UUID updateId, long generation, String body) {
      this.id = id;
      this.stackId = stackId;
      this.updateId = updateId;
      this.generation = generation;
      this.body = body;
    }
  }
}
