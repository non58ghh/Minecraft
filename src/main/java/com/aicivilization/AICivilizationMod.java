package com.aicivilization;

import com.aicivilization.command.CivCommands;
import com.aicivilization.config.ModConfig;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.reasoning.AnthropicReasoningProvider;
import com.aicivilization.reasoning.HeuristicReasoningProvider;
import com.aicivilization.reasoning.ReasoningProvider;
import com.aicivilization.reasoning.ReasoningScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.random.RandomGenerator;

public final class AICivilizationMod implements ModInitializer {

	public static final String MOD_ID = "aicivilization";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String[] NAME_POOL = {
			"Elias", "Marcus", "Iris", "Talia", "Osric", "Nadia", "Petra", "Corwin",
			"Sable", "Rowan", "Idris", "Lyra", "Bram", "Thessaly", "Osgood", "Wren"
	};

	public static EntityType<AgentEntity> AGENT_ENTITY_TYPE;

	private static ReasoningScheduler reasoningScheduler;

	@Override
	public void onInitialize() {
		ModConfig config = ModConfig.loadOrCreate();

		AGENT_ENTITY_TYPE = Registry.register(Registries.ENTITY_TYPE, Identifier.of(MOD_ID, "agent"),
				FabricEntityTypeBuilder.createMob()
						.spawnGroup(SpawnGroup.CREATURE)
						.entityFactory(AgentEntity::new)
						.dimensions(EntityDimensions.fixed(0.6f, 1.95f))
						.build());
		FabricDefaultAttributeRegistry.register(AGENT_ENTITY_TYPE, AgentEntity.createAgentAttributes());

		ReasoningProvider provider = "anthropic".equalsIgnoreCase(config.llmProvider)
				? new AnthropicReasoningProvider(config.resolveAnthropicApiKey(), config.anthropicModel, config.anthropicMaxTokens)
				: new HeuristicReasoningProvider();
		reasoningScheduler = new ReasoningScheduler(provider, config.reasoningIntervalTicks);

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> CivCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

		LOGGER.info("AI Civilization initialized (llmProvider={}).", config.llmProvider);
	}

	private void onEndServerTick(net.minecraft.server.MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			PopulationRegistry registry = PopulationRegistry.get(world);
			EventLog log = EventLog.get(world);
			long tick = world.getTime();
			for (AgentMind mind : registry.population().allMinds()) {
				if (mind.isAlive()) {
					reasoningScheduler.maybeInvoke(mind, tick, log, server);
				}
			}
		}
	}

	public static String randomAgentName(RandomGenerator rng) {
		return NAME_POOL[rng.nextInt(NAME_POOL.length)];
	}
}
