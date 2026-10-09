package com.aicivilization.reasoning;

import java.util.List;

/**
 * A conversation as written for two agents: what it was about, what each
 * said (lines already prefixed with the speaker's name), the one thing each
 * takes away from it, and what they actually agreed to do.
 */
public record Dialogue(String topic, List<String> lines, String firstRemembers, String secondRemembers,
		List<Agreement> agreements) {
	public Dialogue {
		lines = List.copyOf(lines);
		agreements = List.copyOf(agreements);
	}

	/**
	 * Something agreed in the conversation, still to be checked against the
	 * world before it's carried out. {@code first} says which side it
	 * concerns: the giver of a gift, or who takes on a plan ({@code both}
	 * for the two of them).
	 */
	public record Agreement(Kind kind, boolean first, boolean both, String item, int count, String activity, String goal) {
	}

	public enum Kind {
		/** One hands the other something, now. A swap is two of these. */
		GIVE,
		/** One (or both) means to do something: becomes a goal. */
		PLAN,
		/** They'll build a home together. */
		BUILD_TOGETHER
	}
}
