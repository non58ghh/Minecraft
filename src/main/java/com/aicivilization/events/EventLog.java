package com.aicivilization.events;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, structured log of {@link SimEvent}s — the machine-readable
 * record behind {@code /civ why}. Agents never read this (that would be
 * global knowledge); it exists purely for external observability.
 */
public final class EventLog extends PersistentState {

	private static final String KEY = "aicivilization_events";
	private static final int MAX_EVENTS = 20_000;

	public static final PersistentState.Type<EventLog> TYPE = new PersistentState.Type<>(
			EventLog::new,
			(tag, registryLookup) -> {
				EventLog log = new EventLog();
				log.readFrom(tag);
				return log;
			},
			DataFixTypes.LEVEL
	);

	private final List<SimEvent> events = new ArrayList<>();
	private long nextId = 1;

	public static EventLog get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, KEY);
	}

	public SimEvent append(long tick, EventType type, List<UUID> subjects, String summary, List<Cause> causes) {
		SimEvent event = new SimEvent(nextId++, tick, type, subjects, summary, causes);
		events.add(event);
		if (events.size() > MAX_EVENTS) {
			events.remove(0);
		}
		markDirty();
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

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		NbtList list = new NbtList();
		for (SimEvent event : events) {
			list.add(write(event));
		}
		nbt.put("events", list);
		nbt.putLong("nextId", nextId);
		return nbt;
	}

	private void readFrom(NbtCompound nbt) {
		NbtList list = nbt.getList("events", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			events.add(read(list.getCompound(i)));
		}
		nextId = nbt.getLong("nextId");
		if (nextId < 1) {
			nextId = 1;
		}
	}

	private static NbtCompound write(SimEvent event) {
		NbtCompound tag = new NbtCompound();
		tag.putLong("id", event.id());
		tag.putLong("tick", event.tick());
		tag.putString("type", event.type().name());
		tag.putString("summary", event.summary());

		NbtList subjects = new NbtList();
		for (UUID subject : event.subjects()) {
			NbtCompound s = new NbtCompound();
			s.putUuid("id", subject);
			subjects.add(s);
		}
		tag.put("subjects", subjects);

		NbtList causes = new NbtList();
		for (Cause cause : event.causes()) {
			NbtCompound c = new NbtCompound();
			c.putString("sourceType", cause.sourceType().name());
			c.putString("sourceId", cause.sourceId());
			c.putString("detail", cause.detail() == null ? "" : cause.detail());
			causes.add(c);
		}
		tag.put("causes", causes);
		return tag;
	}

	private static SimEvent read(NbtCompound tag) {
		List<UUID> subjects = new ArrayList<>();
		NbtList subjectsTag = tag.getList("subjects", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < subjectsTag.size(); i++) {
			subjects.add(subjectsTag.getCompound(i).getUuid("id"));
		}

		List<Cause> causes = new ArrayList<>();
		NbtList causesTag = tag.getList("causes", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < causesTag.size(); i++) {
			NbtCompound c = causesTag.getCompound(i);
			causes.add(new Cause(CauseType.valueOf(c.getString("sourceType")), c.getString("sourceId"), c.getString("detail")));
		}

		return new SimEvent(tag.getLong("id"), tag.getLong("tick"), EventType.valueOf(tag.getString("type")),
				subjects, tag.getString("summary"), causes);
	}
}
