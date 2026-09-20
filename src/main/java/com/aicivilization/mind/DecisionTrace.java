package com.aicivilization.mind;

import com.aicivilization.events.Cause;

import java.util.List;
import java.util.Map;

/**
 * A record of one moment-to-moment decision: every candidate intent that was
 * considered, its score and the factors that produced that score, which one
 * was chosen, and the causes (memories/needs) behind that choice. This is
 * what lets {@code /civ why} answer "why did this agent do that?" instead of
 * just "what did this agent do?".
 */
public record DecisionTrace(
		long id,
		long tick,
		List<ScoredIntent> candidates,
		IntentType chosen,
		List<Cause> causes
) {
	public DecisionTrace {
		candidates = List.copyOf(candidates);
		causes = List.copyOf(causes);
	}

	/** One candidate intent's score, with a breakdown of what contributed to it. */
	public record ScoredIntent(IntentType intent, double score, Map<String, Double> factors) {
		public ScoredIntent {
			factors = Map.copyOf(factors);
		}
	}
}
