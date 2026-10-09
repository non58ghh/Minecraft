package com.aicivilization.reasoning;

import java.util.List;

/**
 * A conversation as written for two agents: what it was about, what each
 * said (lines already prefixed with the speaker's name), and the one thing
 * each takes away from it.
 */
public record Dialogue(String topic, List<String> lines, String firstRemembers, String secondRemembers) {
	public Dialogue {
		lines = List.copyOf(lines);
	}
}
