package com.aicivilization.events;

/**
 * One typed link in a causal chain: "this decision/event happened because of
 * that memory / perception / need-state / prior event". {@code sourceId} is
 * a free-form identifier whose meaning depends on {@code sourceType} (a
 * memory id, a percept id, a need name, or another event id). {@code detail}
 * is an optional human-readable note (e.g. "hunger=0.81") for display in
 * {@code /civ why} without having to re-resolve the id.
 *
 * <p>Plain data holder, no Minecraft dependency, so it can be produced by
 * {@code com.aicivilization.mind} without pulling engine types into the
 * mind's cognition boundary.
 */
public record Cause(CauseType sourceType, String sourceId, String detail) {

	public static Cause memory(long memoryId, String detail) {
		return new Cause(CauseType.MEMORY, Long.toString(memoryId), detail);
	}

	public static Cause perception(String perceptId, String detail) {
		return new Cause(CauseType.PERCEPTION, perceptId, detail);
	}

	public static Cause needState(String needName, double value) {
		return new Cause(CauseType.NEED_STATE, needName, needName + "=" + value);
	}

	public static Cause event(long eventId, String detail) {
		return new Cause(CauseType.EVENT, Long.toString(eventId), detail);
	}
}
