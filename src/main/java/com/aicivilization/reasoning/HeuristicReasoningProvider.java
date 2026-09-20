package com.aicivilization.reasoning;

import com.aicivilization.mind.IntentType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * The no-network stand-in for an LLM reasoning pass. Deliberately weighs
 * needs, personality, and randomness together and samples probabilistically
 * (softmax over scores), rather than an {@code if trait > threshold} rule
 * table — the latter would quietly become a disguised profession system,
 * where the same personality always produces the same "role". Two agents
 * with similar personalities can and will form different goals here.
 */
public final class HeuristicReasoningProvider implements ReasoningProvider {

	private static final double TEMPERATURE = 0.35;

	private static final Map<IntentType, String> GOAL_TEMPLATES = new LinkedHashMap<>();

	static {
		GOAL_TEMPLATES.put(IntentType.FORAGE_FOOD, "become better at finding food reliably");
		GOAL_TEMPLATES.put(IntentType.SEEK_SAFETY, "find or build a safer place to stay");
		GOAL_TEMPLATES.put(IntentType.SOCIALIZE, "get to know others nearby better");
		GOAL_TEMPLATES.put(IntentType.EXPLORE, "learn what lies beyond the area I already know");
		GOAL_TEMPLATES.put(IntentType.REST, "settle into a steadier routine");
	}

	@Override
	public CompletableFuture<ReasoningResult> reason(AgentContext context) {
		Random random = new Random(context.agentName().hashCode() * 31L + context.tick());

		Map<IntentType, Double> scores = new LinkedHashMap<>();
		scores.put(IntentType.FORAGE_FOOD, (1 - context.food()) * 0.7 + context.ambition() * 0.3);
		scores.put(IntentType.SEEK_SAFETY, (1 - context.safety()) * 0.9 + (1 - context.risk()) * 0.2);
		scores.put(IntentType.SOCIALIZE, (1 - context.social()) * 0.6 + context.sociability() * 0.5);
		scores.put(IntentType.EXPLORE, context.curiosity() * 0.7 + (1 - context.belonging()) * 0.1);
		scores.put(IntentType.REST, (context.food() + context.safety() + context.social() + context.belonging()) / 4.0 * 0.4);

		// Avoid re-suggesting a goal the agent is already actively pursuing.
		for (String existing : context.activeGoalDescriptions()) {
			GOAL_TEMPLATES.forEach((intent, template) -> {
				if (existing.contains(template)) {
					scores.merge(intent, -0.5, Double::sum);
				}
			});
		}

		IntentType chosen = sample(scores, random);
		double priority = 0.4 + random.nextDouble() * 0.4;

		java.util.Optional<String> belief = java.util.Optional.empty();
		double beliefConfidence = 0.0;
		boolean anyCrisis = context.food() < 0.15 || context.safety() < 0.15 || context.social() < 0.15 || context.belonging() < 0.15;
		if (anyCrisis) {
			String lowest = lowestNeedName(context);
			belief = java.util.Optional.of("I need to take my " + lowest + " more seriously.");
			beliefConfidence = 0.6 + random.nextDouble() * 0.3;
		}

		ReasoningResult result = new ReasoningResult(
				java.util.Optional.of(GOAL_TEMPLATES.get(chosen)),
				java.util.Optional.of(chosen),
				priority,
				belief,
				beliefConfidence
		);
		return CompletableFuture.completedFuture(result);
	}

	private static String lowestNeedName(AgentContext context) {
		double lowest = Math.min(Math.min(context.food(), context.safety()), Math.min(context.social(), context.belonging()));
		if (lowest == context.food()) {
			return "food";
		}
		if (lowest == context.safety()) {
			return "safety";
		}
		if (lowest == context.social()) {
			return "social connection";
		}
		return "sense of belonging";
	}

	private static IntentType sample(Map<IntentType, Double> scores, Random random) {
		double maxScore = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
		Map<IntentType, Double> weights = new LinkedHashMap<>();
		double total = 0.0;
		for (Map.Entry<IntentType, Double> entry : scores.entrySet()) {
			double weight = Math.exp((entry.getValue() - maxScore) / TEMPERATURE);
			weights.put(entry.getKey(), weight);
			total += weight;
		}
		double roll = random.nextDouble() * total;
		double cumulative = 0.0;
		for (Map.Entry<IntentType, Double> entry : weights.entrySet()) {
			cumulative += entry.getValue();
			if (roll <= cumulative) {
				return entry.getKey();
			}
		}
		return scores.keySet().iterator().next();
	}
}
