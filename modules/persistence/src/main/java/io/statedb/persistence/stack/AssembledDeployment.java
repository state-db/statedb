package io.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AssembledDeployment(JsonNode deployment, int version, List<String> features) {}
