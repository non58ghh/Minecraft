package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The plain-Java population model: every {@link AgentMind} that has ever
 * existed, keyed by agent id, independent of whether it currently has a
 * loaded Minecraft entity. This is what makes minds outlive entities
 * (death, and eventually rebirth/generations) and makes an offline/batch
 * simulation over the whole population possible later.
 *
 * <p>No Minecraft dependency here — {@code PopulationRegistry} (same
 * package) is the thin adapter that persists this via Minecraft's
 * {@code PersistentState}/NBT.
 */
public final class Population {

	private final Map<UUID, AgentMind> minds = new LinkedHashMap<>();

	public AgentMind add(AgentMind mind) {
		minds.put(mind.identity().id(), mind);
		return mind;
	}

	public Optional<AgentMind> getMind(UUID agentId) {
		return Optional.ofNullable(minds.get(agentId));
	}

	public Collection<AgentMind> allMinds() {
		return Collections.unmodifiableCollection(minds.values());
	}

	/** The mind stays in the population after this — only its {@code alive} flag changes. */
	public void recordDeath(UUID agentId) {
		AgentMind mind = minds.get(agentId);
		if (mind != null) {
			mind.markDead();
		}
	}

	public int size() {
		return minds.size();
	}
}
