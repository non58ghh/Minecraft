package com.aicivilization.mind;

/**
 * A design an agent knows how to build, and how it came to know it.
 *
 * @param how         "innate" (the hut), "designed" (its own), "saw" (looked at
 *                    someone's building) or "told" (heard it described)
 * @param source      who it came from (agent name), or empty for innate/designed
 * @param sourceId    that agent's id, or null
 * @param learnedTick when it was learned
 */
public record KnownDesign(Design design, String how, String source, java.util.UUID sourceId, long learnedTick) {

	public static KnownDesign innate(Design design) {
		return new KnownDesign(design, "innate", "", null, 0);
	}
}
