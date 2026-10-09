package com.aicivilization.mind;

/**
 * A longer-term aspiration an agent has formed for itself (e.g. "become
 * self-sufficient"). {@code relatedIntent}, when non-null, is the
 * {@link IntentType} this goal would be pursued through, which is how a
 * goal biases moment-to-moment intent scoring without dictating a role:
 * nothing prevents two very different agents from forming the same goal, or
 * the same agent from abandoning it. Goals are only ever created by the
 * agent's own reasoning (see {@code AgentMind.addGoal}) — never assigned by
 * the simulation.
 */
public record Goal(long id, String description, double priority, IntentType relatedIntent, long createdTick, boolean active,
		int progress, String targetItem, int targetCount) {
	public Goal {
		priority = Math.max(0.0, Math.min(1.0, priority));
		targetCount = targetItem == null ? 0 : Math.max(1, targetCount);
	}

	public Goal(long id, String description, double priority, IntentType relatedIntent, long createdTick, boolean active) {
		this(id, description, priority, relatedIntent, createdTick, active, 0, null, 0);
	}

	/** Whether this goal names a thing to have: so many of an item (an item id such as "minecraft:iron_pickaxe"). */
	public boolean hasTarget() {
		return targetItem != null;
	}

	public Goal deactivated() {
		return new Goal(id, description, priority, relatedIntent, createdTick, false, progress, targetItem, targetCount);
	}

	public Goal advanced() {
		return new Goal(id, description, priority, relatedIntent, createdTick, active, progress + 1, targetItem, targetCount);
	}
}
