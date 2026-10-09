package com.aicivilization.population;

import com.aicivilization.events.Cause;
import com.aicivilization.events.CauseType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.DecisionTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The "because of" for an event logged without explicit causes: what the
 * agent had decided to do when it happened, and the few things that weighed
 * most in that decision (from its {@link DecisionTrace}).
 */
public final class EventExplainer {

	/** Factors smaller than this don't explain much. */
	private static final double MIN_FACTOR = 0.05;
	private static final int MAX_FACTORS = 3;

	private EventExplainer() {
	}

	public static List<Cause> why(Population population, UUID agentId) {
		AgentMind mind = population.getMind(agentId).orElse(null);
		if (mind == null || mind.recentDecisions().isEmpty()) {
			return List.of();
		}
		List<DecisionTrace> decisions = mind.recentDecisions();
		DecisionTrace last = decisions.get(decisions.size() - 1);
		List<Cause> causes = new ArrayList<>();
		causes.add(new Cause(CauseType.DECISION, Long.toString(last.id()),
				mind.identity().name() + " had decided to " + last.chosen().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
		last.candidates().stream()
				.filter(c -> c.intent() == last.chosen())
				.findFirst()
				.ifPresent(chosen -> chosen.factors().entrySet().stream()
						.filter(f -> f.getValue() >= MIN_FACTOR && !f.getKey().equals("jitter"))
						.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
						.limit(MAX_FACTORS)
						.forEach(f -> causes.add(new Cause(CauseType.FACTOR, f.getKey(),
								f.getKey() + " " + String.format(Locale.ROOT, "%.2f", f.getValue())))));
		return causes;
	}
}
