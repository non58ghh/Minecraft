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
		double beliefConfidence,
		/** What the goal is to have, as an item name ("iron_pickaxe"), checked before use; empty for most goals. */
		Optional<String> targetItem,
		int targetCount,
		/** Why the call came to nothing (an API error, a reply cut off), or null if it worked. */
		String failure
) {
	public ReasoningResult(Optional<String> goalDescription, Optional<IntentType> relatedIntent, double goalPriority,
			Optional<String> beliefStatement, double beliefConfidence, Optional<String> targetItem, int targetCount) {
		this(goalDescription, relatedIntent, goalPriority, beliefStatement, beliefConfidence, targetItem, targetCount, null);
	}

	public ReasoningResult(Optional<String> goalDescription, Optional<IntentType> relatedIntent, double goalPriority,
			Optional<String> beliefStatement, double beliefConfidence) {
		this(goalDescription, relatedIntent, goalPriority, beliefStatement, beliefConfidence, Optional.empty(), 0);
	}

	public static ReasoningResult none() {
		return new ReasoningResult(Optional.empty(), Optional.empty(), 0.0, Optional.empty(), 0.0);
	}

	/** The call didn't come through; {@code why} is for the event log, e.g. "the reply was cut off". */
	public static ReasoningResult failed(String why) {
		return new ReasoningResult(Optional.empty(), Optional.empty(), 0.0, Optional.empty(), 0.0, Optional.empty(), 0, why);
	}

	public boolean isFailure() {
		return failure != null;
	}
}
