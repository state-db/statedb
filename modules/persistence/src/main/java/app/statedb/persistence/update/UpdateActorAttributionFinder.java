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

  public record ActorRef(UUID updateId, UUID startedByUserId, UUID startedByApiTokenId) {}

  public Map<UUID, UpdateActorAttribution> forUpdates(Collection<StackUpdate> updates) {
    if (updates.isEmpty()) {
      return Map.of();
    }
    return forUpdateRefs(
        updates.stream()
            .map(u -> new ActorRef(u.id, u.startedByUserId, u.startedByApiTokenId))
            .toList());
  }

  public Map<UUID, UpdateActorAttribution> forUpdateRefs(Collection<ActorRef> refs) {
    if (refs.isEmpty()) {
      return Map.of();
    }
    Map<UUID, User> users = usersById(refs);
    Map<UUID, ApiToken> tokens = tokensById(refs);
    Map<UUID, UpdateActorAttribution> actors = new HashMap<>();
    for (ActorRef ref : refs) {
      UpdateActorAttribution actor = actorFor(ref, users, tokens);
      if (actor != null) {
        actors.put(ref.updateId(), actor);
      }
    }
    return actors;
  }

  private static Map<UUID, User> usersById(Collection<ActorRef> refs) {
    List<UUID> userIds =
        refs.stream().map(ActorRef::startedByUserId).filter(Objects::nonNull).distinct().toList();
    if (userIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return User.<User>list("id in ?1", userIds).stream()
        .collect(Collectors.toMap(user -> user.id, Function.identity()));
  }

  private static Map<UUID, ApiToken> tokensById(Collection<ActorRef> refs) {
    List<UUID> tokenIds =
        refs.stream()
            .map(ActorRef::startedByApiTokenId)
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
      ActorRef ref, Map<UUID, User> users, Map<UUID, ApiToken> tokens) {
    User user = users.get(ref.startedByUserId());
    ApiToken token = tokens.get(ref.startedByApiTokenId());
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
        user != null ? user.id : ref.startedByUserId(),
        token != null ? token.id : ref.startedByApiTokenId(),
        user != null ? user.displayName : null,
        user != null ? user.email : null,
        fallback,
        accessProfile,
        (user != null && user.deactivatedAt != null) || (token != null && token.revokedAt != null));
  }
}
