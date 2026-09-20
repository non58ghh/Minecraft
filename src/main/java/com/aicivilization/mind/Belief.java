package com.aicivilization.mind;

/**
 * Something an agent has come to believe, with a confidence level and a
 * provenance chain like any other piece of knowledge. Never seeded by the
 * simulation — beliefs only ever come from {@code AgentMind.formBelief},
 * which is only called as a result of the agent's own experience/reasoning.
 */
public record Belief(long id, String statement, double confidence, Provenance provenance, long formedTick) {
	public Belief {
		confidence = Math.max(0.0, Math.min(1.0, confidence));
	}
}
