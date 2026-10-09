package com.aicivilization.reasoning;

import java.util.List;

/**
 * Everything a {@link ReasoningProvider} is given to reason about one
 * agent's situation — built <em>only</em> from that agent's own
 * {@code AgentMind} (its needs, personality, memories, goals). Never from
 * global world state, so the epistemic rule holds even when an LLM is doing
 * the thinking: it can only "know" what's in this context.
 */
public record AgentContext(
		String agentName,
		long tick,
		double curiosity,
		double risk,
		double sociability,
		double ambition,
		double food,
		double safety,
		double social,
		double belonging,
		List<String> recentMemories,
		List<String> activeGoalDescriptions,
		List<String> carrying,
		List<String> recentBeliefs
) {
	/** A compact natural-language description an LLM provider can reason over. */
	public String toPromptSummary() {
		StringBuilder sb = new StringBuilder();
		sb.append("You are simulating the inner thoughts of ").append(agentName)
				.append(", an autonomous agent living in a shared world with others.\n");
		sb.append(String.format(
				"Personality: curiosity=%.2f, risk=%.2f, sociability=%.2f, ambition=%.2f\n",
				curiosity, risk, sociability, ambition));
		sb.append(String.format(
				"Needs (1.0 = fully satisfied, 0.0 = critical): food=%.2f, safety=%.2f, social=%.2f, belonging=%.2f\n",
				food, safety, social, belonging));
		List<String> critical = new java.util.ArrayList<>();
		if (food < 0.3) critical.add("food (starving: finding something to eat comes first)");
		if (safety < 0.3) critical.add("safety (feels exposed and in danger)");
		if (social < 0.3) critical.add("social (lonely)");
		if (belonging < 0.3) critical.add("belonging (feels rootless)");
		if (!critical.isEmpty()) {
			sb.append("URGENT needs right now: ").append(String.join("; ", critical)).append('\n');
		}
		sb.append("Carrying: ").append(carrying.isEmpty() ? "nothing" : String.join(", ", carrying)).append('\n');
		sb.append("Recent memories:\n");
		if (recentMemories.isEmpty()) {
			sb.append("  (none yet)\n");
		} else {
			for (String memory : recentMemories) {
				sb.append("  - ").append(memory).append('\n');
			}
		}
		sb.append("Current goals:\n");
		if (activeGoalDescriptions.isEmpty()) {
			sb.append("  (none yet)\n");
		} else {
			for (String goal : activeGoalDescriptions) {
				sb.append("  - ").append(goal).append('\n');
			}
		}
		if (!recentBeliefs.isEmpty()) {
			sb.append("Things this agent already believes (do not restate these; only add a belief that is genuinely new):\n");
			for (String belief : recentBeliefs) {
				sb.append("  - ").append(belief).append('\n');
			}
		}
		sb.append("Nobody has told this agent what its role or profession is; any goal it forms "
				+ "must come from its own needs, personality, and experience, not an assigned job.\n");
		sb.append("Respond with ONLY a single-line JSON object of this exact shape, no other text. "
				+ "Keep goal and belief to 12 words or fewer each; use an empty string for a "
				+ "field you have nothing to add to:\n");
		sb.append("{\"goal\":\"...\",\"relatedIntent\":\"FORAGE_FOOD|SEEK_SAFETY|SOCIALIZE|EXPLORE|REST|IDLE|GATHER_MATERIALS|BUILD_SHELTER|FARM\","
				+ "\"priority\":0.0,\"belief\":\"...\",\"beliefConfidence\":0.0}\n");
		return sb.toString();
	}
}
