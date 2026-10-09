package com.aicivilization.entity;

/**
 * Plain helpers for showing agents to vanilla and Bedrock clients as players.
 * A player's name tag comes from its profile name, which the protocol limits
 * to 16 characters of letters, digits and underscores.
 */
public final class PlayerDisguise {

	static final int MAX_PROFILE_NAME = 16;

	private PlayerDisguise() {
	}

	/** The agent's name made safe for a player profile; "Agent" if nothing usable is left. */
	public static String profileName(String name) {
		if (name == null) {
			return "Agent";
		}
		String cleaned = name.replaceAll("[^A-Za-z0-9_]", "");
		if (cleaned.isEmpty()) {
			return "Agent";
		}
		return cleaned.length() > MAX_PROFILE_NAME ? cleaned.substring(0, MAX_PROFILE_NAME) : cleaned;
	}
}
