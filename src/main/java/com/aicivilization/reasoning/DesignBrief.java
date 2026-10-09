package com.aicivilization.reasoning;

import com.aicivilization.mind.DesignValidator;
import java.util.List;

/**
 * What an agent brings to designing its own home: who it is and what it has
 * lived through. Built only from its own mind, like {@link AgentContext}.
 */
public record DesignBrief(
		String agentName,
		long seed,
		double curiosity,
		double risk,
		double sociability,
		double ambition,
		List<String> recentMemories
) {
	/** The one-time prompt asking Claude to draw this agent's home. */
	public String toPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("You are ").append(agentName).append(", an autonomous agent in a blocky survival world, ")
				.append("about to build your own home from wooden planks. Design it.\n");
		sb.append(String.format("Your personality: curiosity=%.2f, risk=%.2f, sociability=%.2f, ambition=%.2f\n",
				curiosity, risk, sociability, ambition));
		if (!recentMemories.isEmpty()) {
			sb.append("What you have lived through lately:\n");
			for (String memory : recentMemories) {
				sb.append("  - ").append(memory).append('\n');
			}
		}
		sb.append("Draw the building as horizontal layers, bottom layer first. Each layer is a list of rows ")
				.append("(north to south); each row is a string, one character per block (west to east):\n")
				.append("  '#' = a plank block, '.' = open space inside, 'D' = doorway (open), ' ' = nothing.\n")
				.append("Rules: every layer has the same number of rows and every row the same length; width and depth are odd, 3 to ")
				.append(DesignValidator.MAX_SIDE).append("; at most ").append(DesignValidator.MAX_HEIGHT).append(" layers; ")
				.append("between 20 and 70 '#' blocks in total (each one must be chopped and carried, so smaller is easier); ")
				.append("the bottom layer has inside space and a doorway on its outer edge, with a 'D' directly above it too so it is 2 high; ")
				.append("every '.' is walled in on all four sides by '#', '.' or 'D'; every '.' has a '#' somewhere above it (a roof); ")
				.append("all inside space can be reached from the doorway.\n")
				.append("The LAST layer must be the roof: '#' over every cell that is '.' or 'D' in any layer below.\n")
				.append("For example a small hut is [[\"###\",\"#.#\",\"#D#\"],[\"###\",\"#.#\",\"#D#\"],[\"###\",\"###\",\"###\"]].\n")
				.append("Let your personality shape it (size, height, where the door faces, a raised roof, open corners).\n");
		sb.append("Respond with ONLY a single-line JSON object, no other text: ")
				.append("{\"name\":\"2-4 word name for this kind of building\",\"layers\":[[\"###\",\"#.#\",\"#D#\"],...]}\n");
		return sb.toString();
	}
}
