package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The Minecraft-facing persistence adapter for the {@link Population}. This
 * is the one place in {@code population} that's allowed to know about
 * Minecraft's {@code PersistentState}/NBT — {@link Population} and every
 * class in {@code com.aicivilization.mind} stay engine-agnostic.
 */
public final class PopulationRegistry extends PersistentState {

	private static final String KEY = "aicivilization_population";

	public static final PersistentState.Type<PopulationRegistry> TYPE = new PersistentState.Type<>(
			PopulationRegistry::new,
			(tag, registryLookup) -> {
				PopulationRegistry registry = new PopulationRegistry();
				registry.readFrom(tag);
				return registry;
			},
			DataFixTypes.LEVEL
	);

	private final Population population = new Population();
	private final AgentScheduler scheduler = new AgentScheduler();

	public static PopulationRegistry get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, KEY);
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
		markDirty();
		return mind;
	}

	public void recordDeath(UUID agentId) {
		population.recordDeath(agentId);
		scheduler.forget(agentId);
		markDirty();
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		NbtList mindsList = new NbtList();
		for (AgentMind mind : population.allMinds()) {
			mindsList.add(AgentMindNbt.write(mind));
		}
		nbt.put("minds", mindsList);
		return nbt;
	}

	private void readFrom(NbtCompound nbt) {
		NbtList mindsList = nbt.getList("minds", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < mindsList.size(); i++) {
			population.add(AgentMindNbt.read(mindsList.getCompound(i)));
		}
	}
}
