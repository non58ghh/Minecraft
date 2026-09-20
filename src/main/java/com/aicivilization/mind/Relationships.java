package com.aicivilization.mind;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** All of one agent's relationships to specific other agents, keyed by their id. */
public final class Relationships {

	private final Map<UUID, RelationshipData> byAgent = new HashMap<>();

	/** Gets (creating a neutral default if absent) the relationship toward {@code agentId}. */
	public RelationshipData with(UUID agentId) {
		return byAgent.computeIfAbsent(agentId, id -> new RelationshipData());
	}

	public Optional<RelationshipData> get(UUID agentId) {
		return Optional.ofNullable(byAgent.get(agentId));
	}

	public Map<UUID, RelationshipData> asMap() {
		return Collections.unmodifiableMap(byAgent);
	}

	/** For persistence: restores a previously-serialized relationship. */
	public void restore(UUID agentId, RelationshipData data) {
		byAgent.put(agentId, data);
	}
}
