package io.statedb.persistence.update;

import java.util.UUID;

public record StartedUpdate(UUID updateId, int version) {}
