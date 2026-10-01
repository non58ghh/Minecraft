package com.aicivilization.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import com.mojang.serialization.Codec;

/**
 * Append-only, structured log of {@link SimEvent}s — the machine-readable
 * record behind {@code /civ why}. Agents never read this (that would be
 * global knowledge); it exists purely for external observability.
 */
public final class EventLog extends SavedData {

	private static final int MAX_EVENTS = 20_000;

	private static final Codec<EventLog> CODEC = CompoundTag.CODEC.xmap(EventLog::fromTag, EventLog::toTag);

	public static final SavedDataType<EventLog> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "events"),
			EventLog::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	private final List<SimEvent> events = new ArrayList<>();
	private long nextId = 1;

	public static EventLog get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public SimEvent append(long tick, EventType type, List<UUID> subjects, String summary, List<Cause> causes) {
		SimEvent event = new SimEvent(nextId++, tick, type, subjects, summary, causes);
		events.add(event);
		if (events.size() > MAX_EVENTS) {
			events.remove(0);
		}
		setDirty();
		return event;
	}

	public List<SimEvent> all() {
		return List.copyOf(events);
	}

	/** Most recent events involving {@code agentId}, newest first. */
	public List<SimEvent> forAgent(UUID agentId, int limit) {
		List<SimEvent> result = new ArrayList<>();
		for (int i = events.size() - 1; i >= 0 && result.size() < limit; i--) {
			SimEvent event = events.get(i);
			if (event.subjects().contains(agentId)) {
				result.add(event);
			}
		}
		return result;
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (SimEvent event : events) {
			list.add(write(event));
		}
		nbt.put("events", list);
		nbt.putLong("nextId", nextId);
		return nbt;
	}

	private static EventLog fromTag(CompoundTag nbt) {
		EventLog log = new EventLog();
		ListTag list = nbt.getListOrEmpty("events");
		for (int i = 0; i < list.size(); i++) {
			log.events.add(read(list.getCompoundOrEmpty(i)));
		}
		log.nextId = Math.max(1, nbt.getLongOr("nextId", 1));
		return log;
	}

	private static CompoundTag write(SimEvent event) {
		CompoundTag tag = new CompoundTag();
		tag.putLong("id", event.id());
		tag.putLong("tick", event.tick());
		tag.putString("type", event.type().name());
		tag.putString("summary", event.summary());

		ListTag subjects = new ListTag();
		for (UUID subject : event.subjects()) {
			CompoundTag s = new CompoundTag();
			s.store("id", UUIDUtil.CODEC, subject);
			subjects.add(s);
		}
		tag.put("subjects", subjects);

		ListTag causes = new ListTag();
		for (Cause cause : event.causes()) {
			CompoundTag c = new CompoundTag();
			c.putString("sourceType", cause.sourceType().name());
			c.putString("sourceId", cause.sourceId());
			c.putString("detail", cause.detail() == null ? "" : cause.detail());
			causes.add(c);
		}
		tag.put("causes", causes);
		return tag;
	}

	private static SimEvent read(CompoundTag tag) {
		List<UUID> subjects = new ArrayList<>();
		ListTag subjectsTag = tag.getListOrEmpty("subjects");
		for (int i = 0; i < subjectsTag.size(); i++) {
			subjects.add(subjectsTag.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElseThrow());
		}

		List<Cause> causes = new ArrayList<>();
		ListTag causesTag = tag.getListOrEmpty("causes");
		for (int i = 0; i < causesTag.size(); i++) {
			CompoundTag c = causesTag.getCompoundOrEmpty(i);
			causes.add(new Cause(CauseType.valueOf(c.getStringOr("sourceType", "")), c.getStringOr("sourceId", ""), c.getStringOr("detail", "")));
		}

		return new SimEvent(tag.getLongOr("id", 0), tag.getLongOr("tick", 0), EventType.valueOf(tag.getStringOr("type", "")),
				subjects, tag.getStringOr("summary", ""), causes);
	}
}
