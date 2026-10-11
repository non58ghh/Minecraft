package com.aicivilization.population;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.observer.Guardians;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What a Guardian's support brings: once a game day, each living agent with
 * a Guardian receives a parcel of food sent in their name. It arrives like
 * any gift: the agent remembers who it came from and the chronicle records
 * it. Nothing else changes for them; what they do with it is up to them.
 */
public final class SupplyDrops extends SavedData {

	private static final Codec<SupplyDrops> CODEC = CompoundTag.CODEC.xmap(SupplyDrops::fromTag, SupplyDrops::toTag);

	public static final SavedDataType<SupplyDrops> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "supply_drops"),
			SupplyDrops::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	/** One parcel a game day. */
	public static final long EVERY_TICKS = 24000;
	/** What a parcel holds: item id and count. */
	static final List<Map.Entry<String, Integer>> PARCEL = List.of(
			Map.entry("minecraft:bread", 3), Map.entry("minecraft:apple", 2));

	/** When each agent last had a parcel. */
	private final Map<UUID, Long> lastDrop = new HashMap<>();

	public static SupplyDrops get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Called now and then while the simulation runs: sends whatever parcels are due. */
	public static void tick(ServerLevel world, Guardians guardians) {
		if (guardians == null) {
			return;
		}
		SupplyDrops drops = get(world);
		long tick = world.getGameTime();
		EventLog log = EventLog.get(world);
		for (AgentMind mind : PopulationRegistry.get(world).population().allMinds()) {
			Guardians.Guardian guardian = mind.isAlive() ? guardians.of(mind.identity().id()) : null;
			if (guardian != null && drops.due(mind.identity().id(), tick)) {
				deliver(mind, guardian.name(), tick, log);
				drops.lastDrop.put(mind.identity().id(), tick);
				drops.setDirty();
			}
		}
	}

	boolean due(UUID agent, long tick) {
		Long last = lastDrop.get(agent);
		return last == null || tick - last >= EVERY_TICKS;
	}

	static void deliver(AgentMind mind, String guardian, long tick, EventLog log) {
		for (Map.Entry<String, Integer> item : PARCEL) {
			mind.receiveItem(tick, item.getKey(), item.getValue());
		}
		String contents = contents();
		mind.perceive(tick, "A supply parcel from my Guardian, " + guardian + ", reached me: " + contents + ".", 0.5, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				"A supply parcel from " + guardian + " reached " + mind.identity().name() + ": " + contents + ".", List.of());
	}

	/** "3 bread and 2 apple". */
	static String contents() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < PARCEL.size(); i++) {
			if (i > 0) {
				b.append(i == PARCEL.size() - 1 ? " and " : ", ");
			}
			b.append(PARCEL.get(i).getValue()).append(' ').append(PARCEL.get(i).getKey().replaceFirst("^[^:]*:", "").replace('_', ' '));
		}
		return b.toString();
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		CompoundTag last = new CompoundTag();
		lastDrop.forEach((id, t) -> last.putLong(id.toString(), t));
		nbt.put("lastDrop", last);
		return nbt;
	}

	private static SupplyDrops fromTag(CompoundTag nbt) {
		SupplyDrops d = new SupplyDrops();
		CompoundTag last = nbt.getCompoundOrEmpty("lastDrop");
		for (String key : last.keySet()) {
			try {
				d.lastDrop.put(UUID.fromString(key), last.getLongOr(key, 0));
			} catch (IllegalArgumentException e) {
				// Not an agent id: skip it.
			}
		}
		return d;
	}
}
