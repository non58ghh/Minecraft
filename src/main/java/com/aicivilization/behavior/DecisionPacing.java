package com.aicivilization.behavior;

import com.aicivilization.mind.IntentType;

/**
 * When an agent's fast system should re-decide, and which decisions are
 * worth an event-log entry. Pure logic with no Minecraft types, so it is
 * unit-testable.
 *
 * <p>An agent re-decides every {@code intervalTicks}. When a task with a
 * destination finishes early (arrived, or the target was lost) it may
 * re-decide sooner, but never within {@code minGapTicks} of the previous
 * decision; otherwise an agent standing next to its social target would
 * decide and converse on every tick.
 */
final class DecisionPacing {

	private final long intervalTicks;
	private final long minGapTicks;

	private long nextDecisionTick = Long.MIN_VALUE;
	private long lastDecisionTick = Long.MIN_VALUE;
	private boolean taskFinished = false;
	private IntentType lastLoggedIntent = null;

	DecisionPacing(long intervalTicks, long minGapTicks) {
		this.intervalTicks = intervalTicks;
		this.minGapTicks = minGapTicks;
	}

	boolean shouldDecide(long tick) {
		if (tick >= nextDecisionTick) {
			return true;
		}
		return taskFinished && tick - lastDecisionTick >= minGapTicks;
	}

	void onDecided(long tick) {
		lastDecisionTick = tick;
		nextDecisionTick = tick + intervalTicks;
		taskFinished = false;
	}

	/** The current task's destination was reached or its target lost. */
	void onTaskFinished() {
		taskFinished = true;
	}

	/**
	 * Whether a decision should be recorded as a {@code DECISION} event:
	 * only when the choice changes. Every decision is still kept in the
	 * mind's own recent-decision history.
	 */
	boolean shouldLog(IntentType chosen) {
		if (chosen == lastLoggedIntent) {
			return false;
		}
		lastLoggedIntent = chosen;
		return true;
	}
}
