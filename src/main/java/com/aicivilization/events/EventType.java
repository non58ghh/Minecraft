package com.aicivilization.events;

/** Kinds of structured events recorded to the {@link EventLog}. */
public enum EventType {
	SPAWN,
	PERCEIVED,
	DECISION,
	CONVERSATION,
	TOLD,
	REASONING_INVOKED,
	REASONING_RESULT,
	NEED_CRISIS,
	DEATH
}
