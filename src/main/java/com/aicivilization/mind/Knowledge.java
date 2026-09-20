package com.aicivilization.mind;

import java.util.List;

/**
 * What an agent currently "knows", as distinct from what it has
 * "experienced" ({@link MemoryStream}). A memory is an event ("Marcus told
 * me iron is east of town"); knowledge is the belief that follows from it
 * ("iron is east of town"). For Milestone 1 this is kept as a lightweight
 * read-through summary computed on demand from the memory stream, rather
 * than an independently-written field, so it can never drift out of sync
 * with what the agent actually experienced. A real consolidation/decay
 * model (where knowledge outlives the memory that produced it) is future
 * work — this is the seam it attaches to.
 */
public final class Knowledge {

	private Knowledge() {
	}

	public static List<String> summarize(MemoryStream memories, long currentTick, int limit) {
		return memories.retrieve(currentTick, limit).stream()
				.map(MemoryEntry::description)
				.toList();
	}
}
