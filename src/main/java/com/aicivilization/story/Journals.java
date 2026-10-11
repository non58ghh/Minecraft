package com.aicivilization.story;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.observer.Guardians;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.reasoning.JournalBrief;
import com.aicivilization.reasoning.ReasoningProvider;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Journals kept for agents who have a Guardian: once a real-time day (by
 * default), an entry in the agent's own voice from what they remember since
 * the last one, written by the strong model and shown on their page. The
 * first entry comes as soon as they have a Guardian. Agents without one keep
 * no journal; nothing about their minds changes either way.
 */
public final class Journals extends SavedData {

	private static final Codec<Journals> CODEC = CompoundTag.CODEC.xmap(Journals::fromTag, Journals::toTag);

	public static final SavedDataType<Journals> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "journals"),
			Journals::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	/** Entries kept per agent; older ones drop off. */
	static final int KEEP = 30;
	public static final int CHECK_EVERY_TICKS = 1200;

	/** One journal entry: when (game tick and wall clock) and what was written. */
	public record Entry(long tick, long millis, String text) {
	}

	private final Map<UUID, List<Entry>> entries = new HashMap<>();
	/** Agents whose entry is being written right now. Not saved. */
	private final Set<UUID> writing = new HashSet<>();

	public static Journals get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/** This agent's entries, newest first. */
	public List<Entry> of(UUID agent) {
		List<Entry> list = entries.getOrDefault(agent, List.of());
		List<Entry> out = new ArrayList<>(list);
		java.util.Collections.reverse(out);
		return out;
	}

	/** Whether this agent is due an entry at {@code nowMillis}. */
	boolean due(UUID agent, long nowMillis, long everyMillis) {
		if (writing.contains(agent)) {
			return false;
		}
		List<Entry> list = entries.get(agent);
		return list == null || list.isEmpty() || nowMillis - list.get(list.size() - 1).millis() >= everyMillis;
	}

	void add(UUID agent, Entry entry) {
		List<Entry> list = entries.computeIfAbsent(agent, k -> new ArrayList<>());
		list.add(entry);
		while (list.size() > KEEP) {
			list.remove(0);
		}
		setDirty();
	}

	/** On the server thread, now and then: asks for whatever entries are due, one agent at a time. */
	public static void tick(ServerLevel world, Guardians guardians, ReasoningProvider provider, long everyMillis, Executor mainThread) {
		if (guardians == null) {
			return;
		}
		Journals journals = get(world);
		long tick = world.getGameTime();
		long now = System.currentTimeMillis();
		for (AgentMind mind : PopulationRegistry.get(world).population().allMinds()) {
			UUID id = mind.identity().id();
			if (!mind.isAlive() || guardians.of(id) == null || !journals.due(id, now, everyMillis)) {
				continue;
			}
			List<Entry> past = journals.entries.getOrDefault(id, List.of());
			long since = past.isEmpty() ? Long.MIN_VALUE : past.get(past.size() - 1).tick();
			journals.writing.add(id);
			provider.journal(JournalBrief.of(mind, since, tick))
					.exceptionally(ex -> Optional.empty())
					.thenAccept(text -> mainThread.execute(() -> {
						journals.writing.remove(id);
						text.ifPresent(t -> journals.add(id, new Entry(tick, System.currentTimeMillis(), t)));
					}));
			return; // one at a time: the next due entry waits for the next check
		}
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		CompoundTag all = new CompoundTag();
		entries.forEach((id, list) -> {
			ListTag tags = new ListTag();
			for (Entry e : list) {
				CompoundTag t = new CompoundTag();
				t.putLong("tick", e.tick());
				t.putLong("millis", e.millis());
				t.putString("text", e.text());
				tags.add(t);
			}
			all.put(id.toString(), tags);
		});
		nbt.put("entries", all);
		return nbt;
	}

	private static Journals fromTag(CompoundTag nbt) {
		Journals j = new Journals();
		CompoundTag all = nbt.getCompoundOrEmpty("entries");
		for (String key : all.keySet()) {
			UUID id;
			try {
				id = UUID.fromString(key);
			} catch (IllegalArgumentException e) {
				continue;
			}
			List<Entry> list = new ArrayList<>();
			for (var tag : all.getListOrEmpty(key)) {
				if (tag instanceof CompoundTag t) {
					list.add(new Entry(t.getLongOr("tick", 0), t.getLongOr("millis", 0), t.getStringOr("text", "")));
				}
			}
			j.entries.put(id, list);
		}
		return j;
	}
}
