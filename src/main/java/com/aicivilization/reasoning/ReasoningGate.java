package com.aicivilization.reasoning;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decides whether an agent's deep system is worth invoking right now. Pure
 * logic with no Minecraft types, so it is unit-testable. Every "yes" is a
 * paid LLM call when the Anthropic provider is on, so the rules are:
 *
 * <ul>
 *   <li><b>Crisis</b> is edge-triggered: an agent thinks when it enters a
 *   crisis, or when a different need becomes the critical one, but not again
 *   for the same ongoing crisis. Consecutive crisis calls are at least
 *   {@code crisisCooldownTicks} apart. The fast system already handles the
 *   crisis itself; re-asking every few seconds only repeats the answer.</li>
 *   <li><b>Routine reflection</b> happens at most every {@code intervalTicks},
 *   and only if something changed since the last pass: a new memory, or a
 *   need that moved by at least {@code noveltyThreshold}.</li>
 *   <li><b>Daily cap</b>: at most {@code maxCallsPerDay} passes per agent in
 *   any {@link #DAY_TICKS} window (0 disables the cap). Past it, the agent
 *   runs on its fast system alone until the window rolls over.</li>
 * </ul>
 */
final class ReasoningGate {

	/** A real-time day at the normal 20 ticks per second. */
	static final long DAY_TICKS = 20L * 60 * 60 * 24;

	enum Trigger { CRISIS, ROUTINE, DAYBREAK, NIGHTFALL }

	/** Day or night where the agent is: a change between them is something it notices. */
	static final int UNKNOWN = -1, DAY = 0, NIGHT = 1;

	private final long intervalTicks;
	private final long crisisCooldownTicks;
	private final double noveltyThreshold;
	private final int maxCallsPerDay;
	private final Map<UUID, State> states = new HashMap<>();

	ReasoningGate(long intervalTicks, long crisisCooldownTicks, double noveltyThreshold, int maxCallsPerDay) {
		this.intervalTicks = intervalTicks;
		this.crisisCooldownTicks = crisisCooldownTicks;
		this.noveltyThreshold = noveltyThreshold;
		this.maxCallsPerDay = maxCallsPerDay;
	}

	/**
	 * Returns the reason to think now, or {@code null} for "not now". A
	 * non-null result is recorded as an invocation.
	 *
	 * @param crisisNeed     name of the critical need, or {@code null} if none
	 * @param memoryVersion  changes whenever the agent gains a memory
	 * @param needs          current need values, in a fixed order
	 */
	Trigger check(UUID agentId, long tick, String crisisNeed, long memoryVersion, double[] needs) {
		return check(agentId, tick, crisisNeed, memoryVersion, needs, UNKNOWN);
	}

	/**
	 * As above, also noticing the turn from night to day or day to night
	 * ({@code phase} is {@link #DAY}, {@link #NIGHT} or {@link #UNKNOWN}): a
	 * plan made for the dark shouldn't outlive the dark, so the turn prompts
	 * a fresh look, whatever the interval.
	 */
	Trigger check(UUID agentId, long tick, String crisisNeed, long memoryVersion, double[] needs, int phase) {
		State state = states.computeIfAbsent(agentId, id -> new State());
		boolean turned = phase != UNKNOWN && state.lastPhase != UNKNOWN && phase != state.lastPhase;
		if (phase != UNKNOWN) {
			state.lastPhase = phase;
		}
		if (crisisNeed == null) {
			state.lastCrisisNeed = null;
		}

		if (maxCallsPerDay > 0) {
			if (tick - state.windowStart >= DAY_TICKS) {
				state.windowStart = tick;
				state.callsInWindow = 0;
			}
			if (state.callsInWindow >= maxCallsPerDay) {
				return null;
			}
		}

		Trigger trigger = null;
		if (turned) {
			trigger = phase == DAY ? Trigger.DAYBREAK : Trigger.NIGHTFALL;
		} else if (crisisNeed != null && !crisisNeed.equals(state.lastCrisisNeed)
				&& (!state.invoked || tick - state.lastTick >= crisisCooldownTicks)) {
			trigger = Trigger.CRISIS;
		} else if ((!state.invoked || tick - state.lastTick >= intervalTicks)
				&& isNovel(state, memoryVersion, needs)) {
			trigger = Trigger.ROUTINE;
		}
		if (trigger == null) {
			return null;
		}

		state.invoked = true;
		state.lastTick = tick;
		state.lastCrisisNeed = crisisNeed;
		state.lastMemoryVersion = memoryVersion;
		state.lastNeeds = needs.clone();
		state.callsInWindow++;
		return trigger;
	}

	private boolean isNovel(State state, long memoryVersion, double[] needs) {
		if (!state.invoked || memoryVersion != state.lastMemoryVersion) {
			return true;
		}
		for (int i = 0; i < needs.length; i++) {
			if (Math.abs(needs[i] - state.lastNeeds[i]) >= noveltyThreshold) {
				return true;
			}
		}
		return false;
	}

	private static final class State {
		boolean invoked;
		long lastTick;
		String lastCrisisNeed;
		long lastMemoryVersion;
		double[] lastNeeds;
		long windowStart = Long.MIN_VALUE / 2;
		int callsInWindow;
		int lastPhase = UNKNOWN;
	}
}
