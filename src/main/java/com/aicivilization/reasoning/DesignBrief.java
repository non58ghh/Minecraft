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
	/** The example in the prompt: two rooms, walls 3 high, a raised roof. Kept valid by a test. */
	static final String EXAMPLE = "[[\"#######\",\"#..#..#\",\"#..#..#\",\"#.....#\",\"#.....#\",\"#.....#\",\"###D###\"],"
			+ "[\"#######\",\"#..#..#\",\"#..#..#\",\"#.....#\",\"#.....#\",\"#.....#\",\"###D###\"],"
			+ "[\"#######\",\"#..#..#\",\"#..#..#\",\"#.....#\",\"#.....#\",\"#.....#\",\"#######\"],"
			+ "[\"#######\",\"#######\",\"#######\",\"#######\",\"#######\",\"#######\",\"#######\"],"
			+ "[\"       \",\" ##### \",\" ##### \",\" ##### \",\" ##### \",\" ##### \",\"       \"]]";

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
		sb.append("This is the home you will live in for good, not a shelter for one night: make it somewhere ")
				.append("worth coming back to, with room to live in and something of you in its shape.\n");
		sb.append("Draw the building as horizontal layers, bottom layer first. Each layer is a list of rows ")
				.append("(north to south); each row is a string, one character per block (west to east):\n")
				.append("  '#' = a solid block, '.' = open space inside, 'D' = doorway (open), ' ' = nothing.\n")
				.append("Rules: every layer has the same number of rows and every row the same length; width and depth are odd, 3 to ")
				.append(DesignValidator.MAX_SIDE).append("; at most ").append(DesignValidator.MAX_HEIGHT).append(" layers; ")
				.append("between 40 and ").append(DesignValidator.MAX_SOLIDS).append(" '#' blocks in total; ")
				.append("the bottom layer has inside space and a doorway on its outer edge, with a 'D' directly above it too so it is 2 high; ")
				.append("every '.' is walled in on all four sides by '#', '.' or 'D'; every '.' has a '#' somewhere above it (a roof); ")
				.append("all inside space can be reached from the doorway (leave gaps of '.' in inner walls to pass between rooms).\n")
				.append("The LAST layer must be the roof: '#' over every cell that is '.' or 'D' in any layer below.\n")
				.append("Think bigger than a box: most homes here are 7 to 13 blocks across. Some ideas: separate rooms divided by ")
				.append("inner walls; a long hall; a tall room under a high ceiling; a roof that steps in layer by layer; ")
				.append("an overhanging roof or a covered porch (roof '#' over ' ' cells); an L-shape or open corners (' ' outside); ")
				.append("a tower rising from one corner. Building takes time (every block is chopped and carried), but a ")
				.append("home that matters to you is worth it.\n")
				.append("For example, a two-room house with a raised roof is ").append(EXAMPLE).append(".\n")
				.append("Let your personality shape it (size, rooms, height, where the door faces, the roofline).\n");
		sb.append("Respond with ONLY a single-line JSON object, no other text: ")
				.append("{\"name\":\"2-4 word name for this kind of building\",\"layers\":[[\"###\",\"#.#\",\"#D#\"],...]}\n");
		return sb.toString();
	}
}
