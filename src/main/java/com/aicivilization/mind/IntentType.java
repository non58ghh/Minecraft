package com.aicivilization.mind;

/**
 * The "intent" layer between long-term {@link Goal}s and physical actions:
 * goal "become self-sufficient" -&gt; intent {@code FORAGE_FOOD} -&gt; a
 * Minecraft action ("walk toward nearby cow"), chosen by the embodiment
 * layer. Deliberately small and generic — this is <em>not</em> a profession
 * list; every agent can select every intent, moment to moment, based on its
 * own needs/personality/memories.
 */
public enum IntentType {
	FORAGE_FOOD,
	SEEK_SAFETY,
	SOCIALIZE,
	EXPLORE,
	REST,
	IDLE
}
