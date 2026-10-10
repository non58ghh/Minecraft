package com.aicivilization.mind;

import java.util.UUID;

/**
 * How an agent came to know something. Every {@link MemoryEntry} carries one
 * of these — there is no "just knows" case. This is the concrete mechanism
 * behind the no-global-knowledge rule: nothing can hand an agent a fact
 * without going through one of these four paths.
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

	/**
	 * The agent read this, written down somewhere in the world (a sign).
	 * {@code documentId} is the writing itself; {@code authorId} is the agent
	 * who wrote it and {@code authorMemoryId} the memory of theirs it came
	 * from, or {@code null} and {@code -1} when it wasn't written by an agent
	 * (a player's sign). Like {@link Told}, the chain back to the original
	 * observation survives, and it survives the writer: what's written down
	 * outlasts whoever wrote it.
	 */
	record Read(long documentId, UUID authorId, long authorMemoryId) implements Provenance {
	}
}
