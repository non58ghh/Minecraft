package com.aicivilization.reasoning;

import com.aicivilization.mind.IntentType;

import java.util.Optional;

/**
 * The outcome of one "deep reasoning" pass: at most one new goal and at
 * most one new belief the agent's mind should form. Everything here is
 * plain data — applying it to an {@code AgentMind} happens in the caller
 * (which tags the result {@code Provenance.Inferred} when it does).
 */
public record ReasoningResult(
		Optional<String> goalDescription,
		Optional<IntentType> relatedIntent,
		double goalPriority,
		Optional<String> beliefStatement,
		double beliefConfidence
) {
	public static ReasoningResult none() {
		return new ReasoningResult(Optional.empty(), Optional.empty(), 0.0, Optional.empty(), 0.0);
	}
}
