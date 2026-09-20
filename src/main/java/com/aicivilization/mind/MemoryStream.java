package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An agent's episodic memory stream. The only ways to add a memory are the
 * three {@code add*} methods below, each of which fixes the
 * {@link Provenance} to match how the fact actually arrived — there is no
 * generic "add memory with whatever provenance you like" method, because
 * that would let a careless caller manufacture a {@code Perceived} memory
 * for something the agent never perceived.
 */
public final class MemoryStream {

	/** Soft cap; oldest/least-relevant entries are evicted beyond this. */
	private static final int MAX_ENTRIES = 500;

	private final List<MemoryEntry> entries = new ArrayList<>();
	private long nextId = 1;

	public MemoryEntry addPerceived(long tick, String description, double importance, Set<UUID> participants) {
		return add(new MemoryEntry(nextId++, tick, description, importance, participants, new Provenance.Perceived()));
	}

	public MemoryEntry addInferred(long tick, String description, double importance, Set<UUID> participants, long sourceMemoryId) {
		return add(new MemoryEntry(nextId++, tick, description, importance, participants,
				new Provenance.Inferred(sourceMemoryId)));
	}

	public MemoryEntry addTold(long tick, String description, double importance, Set<UUID> participants,
			UUID tellerId, long tellerMemoryId) {
		return add(new MemoryEntry(nextId++, tick, description, importance, participants,
				new Provenance.Told(tellerId, tellerMemoryId)));
	}

	private MemoryEntry add(MemoryEntry entry) {
		entries.add(entry);
		if (entries.size() > MAX_ENTRIES) {
			evictLeastRelevant(entry.tick());
		}
		return entry;
	}

	private void evictLeastRelevant(long currentTick) {
		entries.stream()
				.min(Comparator.comparingDouble(e -> e.retrievalScore(currentTick)))
				.ifPresent(entries::remove);
	}

	/** Retrieves up to {@code limit} memories, ranked by recency + importance. */
	public List<MemoryEntry> retrieve(long currentTick, int limit) {
		return entries.stream()
				.sorted(Comparator.comparingDouble((MemoryEntry e) -> e.retrievalScore(currentTick)).reversed())
				.limit(limit)
				.toList();
	}

	public List<MemoryEntry> all() {
		return List.copyOf(entries);
	}

	public java.util.Optional<MemoryEntry> byId(long id) {
		return entries.stream().filter(e -> e.id() == id).findFirst();
	}

	public int size() {
		return entries.size();
	}

	/** For persistence: the id the next added memory would get. */
	public long peekNextId() {
		return nextId;
	}

	/** For persistence: replaces all entries with previously-serialized ones. */
	public void restoreState(List<MemoryEntry> restored, long nextIdValue) {
		entries.clear();
		entries.addAll(restored);
		this.nextId = nextIdValue;
	}
}
