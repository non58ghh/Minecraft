package com.aicivilization.mind;

import java.util.UUID;

/**
 * Who an agent is. Fixed at creation, never mutated afterward (age is
 * derived from {@code birthTick}, not stored).
 */
public record Identity(UUID id, String name, long birthTick) {

	/** Age in ticks, given the current world tick. */
	public long ageInTicks(long currentTick) {
		return Math.max(0, currentTick - birthTick);
	}
}
