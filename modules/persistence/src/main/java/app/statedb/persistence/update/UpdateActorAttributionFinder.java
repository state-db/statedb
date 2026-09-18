package app.statedb.persistence.update;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.identity.User;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Batch-resolves update actors without making mutable display names into identifiers. */
@ApplicationScoped
public class UpdateActorAttributionFinder {

  public Map<UUID, UpdateActorAttribution> forUpdates(Collection<StackUpdate> updates) {
    if (updates.isEmpty()) {
      return Map.of();
    }
    Map<UUID, User> users = usersById(updates);
    Map<UUID, ApiToken> tokens = tokensById(updates);
    Map<UUID, UpdateActorAttribution> actors = new HashMap<>();
    for (StackUpdate update : updates) {
      UpdateActorAttribution actor = actorFor(update, users, tokens);
      if (actor != null) {
        actors.put(update.id, actor);
      }
    }
    return actors;
  }

  private static Map<UUID, User> usersById(Collection<StackUpdate> updates) {
    List<UUID> userIds =
        updates.stream().map(u -> u.startedByUserId).filter(Objects::nonNull).distinct().toList();
    if (userIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return User.<User>list("id in ?1", userIds).stream()
        .collect(Collectors.toMap(user -> user.id, Function.identity()));
  }

  private static Map<UUID, ApiToken> tokensById(Collection<StackUpdate> updates) {
    List<UUID> tokenIds =
        updates.stream()
            .map(u -> u.startedByApiTokenId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (tokenIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return ApiToken.<ApiToken>list("id in ?1", tokenIds).stream()
        .collect(Collectors.toMap(token -> token.id, Function.identity()));
  }

  private static UpdateActorAttribution actorFor(
      StackUpdate update, Map<UUID, User> users, Map<UUID, ApiToken> tokens) {
    User user = users.get(update.startedByUserId);
    ApiToken token = tokens.get(update.startedByApiTokenId);
    if (user == null && token == null) {
      return null;
    }
    boolean service = user != null && ServiceAccountCreator.SERVICE_ISS.equals(user.iss);
    String fallback = user != null ? user.iss + ":" + user.sub : "unknown actor";
    String accessProfile =
        token != null && token.kind == ApiToken.Kind.service_account
            ? token.accessProfile.wireValue()
            : null;
    return new UpdateActorAttribution(
        service ? "service" : "human",
        user != null ? user.id : update.startedByUserId,
        token != null ? token.id : update.startedByApiTokenId,
        user != null ? user.displayName : null,
        user != null ? user.email : null,
        fallback,
        accessProfile,
        (user != null && user.deactivatedAt != null) || (token != null && token.revokedAt != null));
  }
}
