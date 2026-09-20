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
		List<String> activeGoalDescriptions
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
		sb.append("Nobody has told this agent what its role or profession is; any goal it forms "
				+ "must come from its own needs, personality, and experience, not an assigned job.\n");
		sb.append("Respond with ONLY a JSON object of this exact shape (omit a field's content "
				+ "with an empty string if you have nothing to add):\n");
		sb.append("{\"goal\":\"...\",\"relatedIntent\":\"FORAGE_FOOD|SEEK_SAFETY|SOCIALIZE|EXPLORE|REST|IDLE\","
				+ "\"priority\":0.0,\"belief\":\"...\",\"beliefConfidence\":0.0}\n");
		return sb.toString();
	}
}
