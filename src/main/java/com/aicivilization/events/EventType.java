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
	/** A reasoning call that came to nothing (an API error, a reply cut off), so it isn't mistaken for an idle mind. */
	REASONING_FAILED,
	NEED_CRISIS,
	DEATH,
	/** An agent hurt by a monster. */
	ATTACKED,
	/** A physical action in the world: chopping, hunting, eating, placing blocks. */
	ACTION,
	/** A notable first or completed project, such as finishing a shelter. */
	MILESTONE
}
