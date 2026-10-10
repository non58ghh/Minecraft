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
	IDLE,
	/** Break down natural trees and collect the drops. */
	GATHER_MATERIALS,
	/** Place carried blocks to put up a small shelter. */
	BUILD_SHELTER,
	/** Grow food: plant and harvest crops, gather seeds, feed animals so they breed. */
	FARM,
	/** Head back to one's own home: to sleep at night, or to feel safe and settled. */
	GO_HOME,
	/** Work through the steps toward a goal that names a thing to have (an iron pickaxe, say). */
	PURSUE_PLAN,
	/** Stand and fight a monster that's close: for its own sake, or someone else's. */
	FIGHT,
	/** Put up a sign here saying something worth knowing: where the trees are, a danger, whose home this is. */
	WRITE_SIGN
}
