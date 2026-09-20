package com.aicivilization.events;

/**
 * What kind of thing produced a {@link Cause}. Used to make the causal chain
 * behind a {@code SimEvent} inspectable (see {@code /civ why}) instead of a
 * bag of untyped ids.
 */
public enum CauseType {
	EVENT,
	MEMORY,
	PERCEPTION,
	NEED_STATE
}
