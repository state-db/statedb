package app.statedb.persistence.authz;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import java.util.Set;

public record CedarResource(EntityUID resourceUid, Set<Entity> entities) {}
