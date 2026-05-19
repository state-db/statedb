package io.statedb.persistence.lock;

import java.util.UUID;

public record LeaseContext(UUID stackId, UUID updateId, UUID lockToken) {}
