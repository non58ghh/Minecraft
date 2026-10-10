package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
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
 * The Minecraft-facing persistence adapter for the {@link Population}. This
 * is the one place in {@code population} that's allowed to know about
 * Minecraft's {@code SavedData}/NBT — {@link Population} and every
 * class in {@code com.aicivilization.mind} stay engine-agnostic.
 */
public final class PopulationRegistry extends SavedData {

	private static final Codec<PopulationRegistry> CODEC = CompoundTag.CODEC.xmap(PopulationRegistry::fromTag, PopulationRegistry::toTag);

	public static final SavedDataType<PopulationRegistry> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "population"),
			PopulationRegistry::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	private final Population population = new Population();
	private final AgentScheduler scheduler = new AgentScheduler();
	/** Packed chunk each agent's body was last seen in, so it can be reloaded when nobody is online. */
	private final Map<UUID, Long> bodyChunks = new HashMap<>();
	/** Chunks this mod has force-loaded, so it only ever releases its own. */
	private final Set<Long> forcedChunks = new HashSet<>();
	/** Chunks with fields agents planted, kept loaded while agents run so crops grow. */
	private final Set<Long> fieldChunks = new java.util.LinkedHashSet<>();
	private static final int MAX_FIELD_CHUNKS = 128;
	/** Founders still to be placed, a few seconds apart (placing them all at once can hang the server). */
	private int foundersPending;
	/** Whether this world's founding has begun, so it's never begun twice. */
	private boolean foundingStarted;

	public static PopulationRegistry get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public Population population() {
		return population;
	}

	public AgentScheduler scheduler() {
		return scheduler;
	}

	/** Creates a brand-new agent mind, with no history, and registers it. */
	public AgentMind createMind(UUID id, String name, long birthTick, RandomGenerator rng) {
		return createMind(id, name, birthTick, Personality.random(rng));
	}

	/** As above, with a given nature (a child takes after its parents). */
	public AgentMind createMind(UUID id, String name, long birthTick, Personality personality) {
		AgentMind mind = new AgentMind(new Identity(id, name, birthTick), personality, Needs.initial());
		population.add(mind);
		setDirty();
		return mind;
	}

	public boolean foundingStarted() {
		return foundingStarted;
	}

	public int foundersPending() {
		return foundersPending;
	}

	public void startFounding(int count) {
		foundingStarted = true;
		foundersPending = count;
		setDirty();
	}

	public void founderPlaced() {
		foundersPending = Math.max(0, foundersPending - 1);
		setDirty();
	}

	public void recordDeath(UUID agentId) {
		population.recordDeath(agentId);
		scheduler.forget(agentId);
		bodyChunks.remove(agentId);
		setDirty();
	}

	public Map<UUID, Long> bodyChunks() {
		return bodyChunks;
	}

	public void recordBodyChunk(UUID agentId, long chunk) {
		Long previous = bodyChunks.put(agentId, chunk);
		if (previous == null || previous != chunk) {
			setDirty();
		}
	}

	public Set<Long> forcedChunks() {
		return forcedChunks;
	}

	public Set<Long> fieldChunks() {
		return fieldChunks;
	}

	/** Remembers a chunk with a field in it; the oldest is forgotten past the cap. */
	public void recordFieldChunk(long chunk) {
		if (fieldChunks.add(chunk)) {
			while (fieldChunks.size() > MAX_FIELD_CHUNKS) {
				fieldChunks.remove(fieldChunks.iterator().next());
			}
			setDirty();
		}
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag mindsList = new ListTag();
		for (AgentMind mind : population.allMinds()) {
			mindsList.add(AgentMindNbt.write(mind));
		}
		nbt.put("minds", mindsList);
		ListTag chunks = new ListTag();
		for (Map.Entry<UUID, Long> e : bodyChunks.entrySet()) {
			CompoundTag c = new CompoundTag();
			c.store("agentId", UUIDUtil.CODEC, e.getKey());
			c.putLong("chunk", e.getValue());
			chunks.add(c);
		}
		nbt.put("bodyChunks", chunks);
		nbt.putLongArray("forcedChunks", forcedChunks.stream().mapToLong(Long::longValue).toArray());
		nbt.putLongArray("fieldChunks", fieldChunks.stream().mapToLong(Long::longValue).toArray());
		nbt.putInt("foundersPending", foundersPending);
		nbt.putBoolean("foundingStarted", foundingStarted);
		return nbt;
	}

	private static PopulationRegistry fromTag(CompoundTag nbt) {
		PopulationRegistry registry = new PopulationRegistry();
		ListTag mindsList = nbt.getListOrEmpty("minds");
		for (int i = 0; i < mindsList.size(); i++) {
			registry.population.add(AgentMindNbt.read(mindsList.getCompoundOrEmpty(i)));
		}
		ListTag chunks = nbt.getListOrEmpty("bodyChunks");
		for (int i = 0; i < chunks.size(); i++) {
			CompoundTag c = chunks.getCompoundOrEmpty(i);
			c.read("agentId", UUIDUtil.CODEC).ifPresent(id -> registry.bodyChunks.put(id, c.getLongOr("chunk", 0L)));
		}
		nbt.getLongArray("fieldChunks").ifPresent(arr -> {
			for (long chunk : arr) {
				registry.fieldChunks.add(chunk);
			}
		});
		registry.foundersPending = nbt.getIntOr("foundersPending", 0);
		registry.foundingStarted = nbt.getBooleanOr("foundingStarted", !registry.population.allMinds().isEmpty());
		nbt.getLongArray("forcedChunks").ifPresent(arr -> {
			for (long chunk : arr) {
				registry.forcedChunks.add(chunk);
			}
		});
		return registry;
	}
}
