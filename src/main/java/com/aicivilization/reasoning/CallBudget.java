package com.aicivilization.reasoning;

import java.util.function.LongSupplier;

/**
 * A ceiling on paid LLM calls, across everything that makes them: so many
 * an hour of real time, refilled steadily (a bucket that fills at
 * {@code perHour} and holds an hour's worth). Agents thinking for
 * themselves come first: they may spend down to the last call. Written
 * conversations and chronicle write-ups are extras, made only while at
 * least {@link #EXTRAS_FLOOR} of the bucket is left, so they never crowd
 * out a mind. Out of calls, minds run on their fast system and the extras
 * wait or go without, as they do when there's no LLM at all.
 */
public final class CallBudget {

	/** Extras only while this share of the hour's calls is left. */
	static final double EXTRAS_FLOOR = 0.5;
	private static final double MILLIS_PER_HOUR = 3_600_000.0;

	private final int perHour;
	private final LongSupplier clock;
	private double level;
	private long lastMillis;

	/** {@code perHour} 0 or less means no ceiling. */
	public CallBudget(int perHour) {
		this(perHour, System::currentTimeMillis);
	}

	CallBudget(int perHour, LongSupplier clock) {
		this.perHour = perHour;
		this.clock = clock;
		this.lastMillis = clock.getAsLong();
		// Start half full, so a restart (every agent in a crisis at once) can't spend the hour in a minute.
		this.level = perHour / 2.0;
	}

	public static CallBudget unlimited() {
		return new CallBudget(0);
	}

	/** Whether a call could be made now. {@code extra}: a write-up rather than an agent's own thinking. */
	public synchronized boolean hasRoom(boolean extra) {
		if (perHour <= 0) {
			return true;
		}
		refill();
		return level >= (extra ? Math.max(1.0, perHour * EXTRAS_FLOOR) : 1.0);
	}

	/** Spends a call if there's room for it; returns whether it did. */
	public synchronized boolean take(boolean extra) {
		if (!hasRoom(extra)) {
			return false;
		}
		if (perHour > 0) {
			level -= 1.0;
		}
		return true;
	}

	private void refill() {
		long now = clock.getAsLong();
		level = Math.min(perHour, level + (now - lastMillis) / MILLIS_PER_HOUR * perHour);
		lastMillis = now;
	}
}
