package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.UUID;
import java.util.random.RandomGenerator;
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
		AgentMind mind = new AgentMind(new Identity(id, name, birthTick), Personality.random(rng), Needs.initial());
		population.add(mind);
		setDirty();
		return mind;
	}

	public void recordDeath(UUID agentId) {
		population.recordDeath(agentId);
		scheduler.forget(agentId);
		setDirty();
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag mindsList = new ListTag();
		for (AgentMind mind : population.allMinds()) {
			mindsList.add(AgentMindNbt.write(mind));
		}
		nbt.put("minds", mindsList);
		return nbt;
	}

	private static PopulationRegistry fromTag(CompoundTag nbt) {
		PopulationRegistry registry = new PopulationRegistry();
		ListTag mindsList = nbt.getListOrEmpty("minds");
		for (int i = 0; i < mindsList.size(); i++) {
			registry.population.add(AgentMindNbt.read(mindsList.getCompoundOrEmpty(i)));
		}
		return registry;
	}
}
