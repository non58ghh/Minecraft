package com.aicivilization.perception;

import java.util.UUID;

/**
 * Marker implemented by {@code AgentEntity} so {@link PerceptionSystem} can
 * recognize other agents in the world without the {@code perception}
 * package having to depend on the concrete {@code entity} package (which
 * itself depends on {@code perception} to run perception against itself).
 */
public interface Embodied {
	UUID agentId();

	String agentDisplayName();
}
