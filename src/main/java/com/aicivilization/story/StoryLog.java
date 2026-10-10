package com.aicivilization.story;

import com.aicivilization.events.EventType;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The chronicle's stories, saved with the world so write-ups (each a paid
 * LLM call) survive a restart. The grouping itself is {@link StoryGrouper};
 * this only persists it. Like the event log, agents never read it.
 */
public final class StoryLog extends SavedData {

	private static final Codec<StoryLog> CODEC = CompoundTag.CODEC.xmap(StoryLog::fromTag, StoryLog::toTag);

	public static final SavedDataType<StoryLog> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "stories"),
			StoryLog::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	private final StoryGrouper grouper = new StoryGrouper();

	public static StoryLog get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public StoryGrouper grouper() {
		return grouper;
	}

	/** Call after changing the grouper (new events taken in, a story written). */
	public void changed() {
		setDirty();
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Story s : grouper.stories()) {
			CompoundTag t = new CompoundTag();
			t.putLong("id", s.id());
			t.putLongArray("events", s.eventIds().stream().mapToLong(Long::longValue).toArray());
			ListTag types = new ListTag();
			s.types().forEach(type -> types.add(StringTag.valueOf(type.name())));
			t.put("types", types);
			ListTag people = new ListTag();
			s.people().forEach(id -> people.add(StringTag.valueOf(id.toString())));
			t.put("people", people);
			t.putLong("first", s.firstTick());
			t.putLong("last", s.lastTick());
			t.putBoolean("closed", s.closed());
			t.putString("headline", s.headline());
			t.putString("text", s.text());
			t.putString("stands", s.stands());
			t.putInt("written", s.writtenEvents());
			list.add(t);
		}
		nbt.put("stories", list);
		ListTag routine = new ListTag();
		grouper.routineByDay().forEach((day, counts) -> counts.forEach((id, n) -> {
			CompoundTag r = new CompoundTag();
			r.putLong("day", day);
			r.putString("agent", id.toString());
			r.putInt("count", n);
			routine.add(r);
		}));
		nbt.put("routine", routine);
		nbt.putLong("nextId", grouper.nextStoryId());
		nbt.putLong("lastEvent", grouper.lastEventId());
		return nbt;
	}

	private static StoryLog fromTag(CompoundTag nbt) {
		StoryLog log = new StoryLog();
		List<Story> stories = new ArrayList<>();
		ListTag list = nbt.getListOrEmpty("stories");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompoundOrEmpty(i);
			List<Long> events = new ArrayList<>();
			for (long id : t.getLongArray("events").orElse(new long[0])) {
				events.add(id);
			}
			Set<EventType> types = EnumSet.noneOf(EventType.class);
			ListTag typeTags = t.getListOrEmpty("types");
			for (int j = 0; j < typeTags.size(); j++) {
				try {
					types.add(EventType.valueOf(typeTags.getStringOr(j, "")));
				} catch (IllegalArgumentException e) {
					// A type from a newer version: leave it out.
				}
			}
			List<UUID> people = new ArrayList<>();
			ListTag peopleTags = t.getListOrEmpty("people");
			for (int j = 0; j < peopleTags.size(); j++) {
				try {
					people.add(UUID.fromString(peopleTags.getStringOr(j, "")));
				} catch (IllegalArgumentException e) {
					// Unreadable id: skip it.
				}
			}
			stories.add(Story.restore(t.getLongOr("id", 0), events, types, people, t.getLongOr("first", 0),
					t.getLongOr("last", 0), t.getBooleanOr("closed", true), t.getStringOr("headline", ""),
					t.getStringOr("text", ""), t.getStringOr("stands", ""), t.getIntOr("written", 0)));
		}
		Map<Long, Map<UUID, Integer>> routine = new LinkedHashMap<>();
		ListTag routineTags = nbt.getListOrEmpty("routine");
		for (int i = 0; i < routineTags.size(); i++) {
			CompoundTag r = routineTags.getCompoundOrEmpty(i);
			try {
				routine.computeIfAbsent(r.getLongOr("day", 0), d -> new LinkedHashMap<>())
						.put(UUID.fromString(r.getStringOr("agent", "")), r.getIntOr("count", 0));
			} catch (IllegalArgumentException e) {
				// Unreadable id: skip it.
			}
		}
		log.grouper.restore(stories, routine, nbt.getLongOr("nextId", 1), nbt.getLongOr("lastEvent", 0));
		return log;
	}
}
