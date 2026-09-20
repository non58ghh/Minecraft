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
public record Goal(long id, String description, double priority, IntentType relatedIntent, long createdTick, boolean active) {
	public Goal {
		priority = Math.max(0.0, Math.min(1.0, priority));
	}

	public Goal deactivated() {
		return new Goal(id, description, priority, relatedIntent, createdTick, false);
	}
}
