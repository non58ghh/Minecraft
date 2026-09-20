package com.aicivilization.mind;

import java.util.UUID;

/**
 * How an agent came to know something. Every {@link MemoryEntry} carries one
 * of these — there is no "just knows" case. This is the concrete mechanism
 * behind the no-global-knowledge rule: nothing can hand an agent a fact
 * without going through one of these three paths.
 */
public sealed interface Provenance {

	/** The agent observed this directly via its own {@code PerceptionSystem}. */
	record Perceived() implements Provenance {
	}

	/**
	 * The agent worked this out itself (a reasoning pass). {@code sourceMemoryId}
	 * points at the memory that triggered the inference, or {@code -1} if none.
	 */
	record Inferred(long sourceMemoryId) implements Provenance {
	}

	/**
	 * Another agent told this agent, in an explicit conversation exchange.
	 * {@code tellerMemoryId} points at the specific memory of the teller's
	 * that was shared, preserving the chain transitively: this memory's
	 * provenance is {@code Told}, but the teller's source memory has its own
	 * provenance (which might itself be {@code Perceived}, {@code Inferred},
	 * or a further {@code Told}), so the full chain back to an original
	 * observation is always reconstructable.
	 */
	record Told(UUID tellerId, long tellerMemoryId) implements Provenance {
	}
}
