package com.aicivilization.reasoning;

import java.util.List;

/**
 * A player has spoken to an agent. Everything the agent could draw on in
 * answering, and nothing more: its own situation and recent experiences,
 * what it already knows of this player, and what was just said.
 */
public record ChatBrief(DialogueBrief.Speaker agent, String playerName, String said, List<String> earlier,
		String timeOfDay) {

	public ChatBrief {
		earlier = List.copyOf(earlier);
	}

	public String toPrompt() {
		return """
				You are %s, one of the people of a small, young settlement in a Minecraft world. You live \
				by foraging, farming, hunting, chopping wood and building your own home; nobody has a set \
				role. A player, %s, someone not of your settlement, has come up and spoken to you.

				About you: %s %s
				Carrying: %s
				What's been on your mind lately:
				%s

				What has passed between you and %s before:
				%s

				Time: %s

				%s just said to you: "%s"

				Answer as yourself, in one or two short sentences of plain speech: friendly, wary, curt or \
				warm as your temperament and situation make you. Ground it only in what you know above; \
				don't invent events, places, people or possessions, and don't promise things you can't do. \
				You can ask for things, refuse, or just talk.

				Reply with JSON only, no other text:
				{"reply": "<what you say>",
				 "remembers": "<one first-person sentence you'll remember from this, naming %s>"}
				""".formatted(agent.name(), playerName, agent.temperament(), agent.situation(),
				agent.carrying().isEmpty() ? "nothing" : agent.carrying(),
				agent.recentExperiences().isEmpty() ? "- nothing much" : "- " + String.join("\n- ", agent.recentExperiences()),
				playerName, earlier.isEmpty() ? "- nothing; you haven't spoken before" : "- " + String.join("\n- ", earlier),
				timeOfDay, playerName, said.replace("\"", "'"), playerName);
	}
}
