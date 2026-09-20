package com.aicivilization.population;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decides, per tick, whether a given agent's mind is due for an update.
 * The point is that "every mind updates every Minecraft tick" must never be
 * baked into the architecture — with 10 agents the distinction barely
 * matters, but it's the seam a real population scheduler (proximity/
 * importance/simulation-mode based, per DESIGN.md's roadmap) attaches to
 * later without a rewrite.
 */
public final class AgentScheduler {

	private static final long ACTIVE_INTERVAL_TICKS = 1;
	private static final long IDLE_INTERVAL_TICKS = 5;
	private static final long DORMANT_INTERVAL_TICKS = 200;

	private final Map<UUID, Long> lastUpdateTick = new HashMap<>();

	public boolean shouldUpdate(UUID agentId, long currentTick, ActivityTier tier) {
		if (tier == ActivityTier.DEAD) {
			return false;
		}
		long interval = switch (tier) {
			case ACTIVE -> ACTIVE_INTERVAL_TICKS;
			case IDLE -> IDLE_INTERVAL_TICKS;
			case DORMANT -> DORMANT_INTERVAL_TICKS;
			case DEAD -> Long.MAX_VALUE;
		};
		long last = lastUpdateTick.getOrDefault(agentId, Long.MIN_VALUE / 2);
		if (currentTick - last >= interval) {
			lastUpdateTick.put(agentId, currentTick);
			return true;
		}
		return false;
	}

	public void forget(UUID agentId) {
		lastUpdateTick.remove(agentId);
	}
}
