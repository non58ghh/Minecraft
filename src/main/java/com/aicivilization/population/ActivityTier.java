package com.aicivilization.population;

/**
 * How aggressively an agent's mind is updated. Milestone 1 only ever
 * produces {@code ACTIVE} and {@code IDLE} (all agents currently have a
 * loaded entity), but the tiers exist now so nothing later has to be
 * retrofitted around an assumption that every mind ticks every Minecraft
 * tick — that assumption doesn't survive going from 10 agents to hundreds
 * or thousands.
 */
public enum ActivityTier {
	/** Embodied, and near a player or other agents: full-rate updates. */
	ACTIVE,
	/** Embodied, but nothing nearby to react to: reduced-rate updates. */
	IDLE,
	/** No loaded entity; mind persists, only passive need decay applies. */
	DORMANT,
	/** Entity has died; mind is frozen historical state. */
	DEAD
}
