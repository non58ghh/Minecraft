package com.aicivilization.mind;

import java.util.Set;
import java.util.UUID;

/**
 * One episodic memory. Immutable, its own id and timestamp — even a memory
 * created from being told something by another agent is a brand-new entry
 * in the listener's own stream, never a shared/copied object (see
 * {@link Provenance.Told}).
 */
public record MemoryEntry(
		long id,
		long tick,
		String description,
		double importance,
		Set<UUID> participants,
		Provenance provenance
) {
	public MemoryEntry {
		participants = Set.copyOf(participants);
		importance = Math.max(0.0, Math.min(1.0, importance));
	}

	/** Recency-weighted, importance-weighted retrieval score at {@code currentTick}. */
	double retrievalScore(long currentTick) {
		long age = Math.max(0, currentTick - tick);
		// Exponential decay of recency over ~24000 ticks (one Minecraft day).
		double recency = Math.exp(-age / 24000.0);
		return 0.5 * recency + 0.5 * importance;
	}
}
