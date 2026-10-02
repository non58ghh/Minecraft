package com.aicivilization;

import com.aicivilization.command.CivCommands;
import com.aicivilization.config.ModConfig;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.observer.ObserverServer;
import com.aicivilization.observer.SnapshotCollector;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.reasoning.AnthropicReasoningProvider;
import com.aicivilization.reasoning.HeuristicReasoningProvider;
import com.aicivilization.reasoning.ReasoningProvider;
import com.aicivilization.reasoning.ReasoningScheduler;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
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

	/** Ticks between observer snapshots (one second). */
	private static final int OBSERVER_INTERVAL_TICKS = 20;

	private static ModConfig config;
	private static volatile boolean simulationEnabled = true;

	private static ObserverServer observerServer;
	private static SnapshotCollector snapshotCollector;

	@Override
	public void onInitialize() {
		ModConfig config = ModConfig.loadOrCreate();
		AICivilizationMod.config = config;
		simulationEnabled = config.simulationEnabled;

		// Without this, Fabric's registry-sync handshake kicks any client that can't
		// prove it knows about aicivilization:agent at login — including Geyser's
		// internal Bedrock-to-Java bridge, which can never install this mod. OPTIONAL
		// means "don't require clients to have this," not "don't sync it" — a real
		// Fabric client with the mod still gets it normally.
		RegistryAttributeHolder.get(Registries.ENTITY_TYPE).addAttribute(RegistryAttribute.OPTIONAL);

		Identifier agentId = Identifier.fromNamespaceAndPath(MOD_ID, "agent");
		ResourceKey<EntityType<?>> agentKey = ResourceKey.create(Registries.ENTITY_TYPE, agentId);
		AGENT_ENTITY_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, agentId,
				FabricEntityType.Builder.createMob(AgentEntity::new, MobCategory.CREATURE, builder -> builder)
						.sized(0.6f, 1.95f)
						.build(agentKey));
		FabricDefaultAttributeRegistry.register(AGENT_ENTITY_TYPE, AgentEntity.createAgentAttributes());
		// Marks the type as server-side for Polymer, so clients without this mod are sent
		// AgentEntity#getPolymerEntityType instead of a type they can't decode.
		PolymerEntityUtils.registerType(AGENT_ENTITY_TYPE);

		ReasoningProvider provider = "anthropic".equalsIgnoreCase(config.llmProvider)
				? new AnthropicReasoningProvider(config.resolveAnthropicApiKey(), config.anthropicModel, config.anthropicMaxTokens)
				: new HeuristicReasoningProvider();
		reasoningScheduler = new ReasoningScheduler(provider, config.reasoningIntervalTicks);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) -> CivCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

		if (config.observerEnabled) {
			snapshotCollector = new SnapshotCollector(config.llmProvider, config.reasoningIntervalTicks,
					AICivilizationMod::isSimulationEnabled);
			ServerLifecycleEvents.SERVER_STARTED.register(server -> startObserver(config.observerPort, config.observerToken));
			ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopObserver());
		}

		LOGGER.info("AI Civilization initialized (llmProvider={}, simulation {}).",
				config.llmProvider, simulationEnabled ? "on" : "off");
	}

	/** False after {@code /civ off}: agents stand still and no reasoning or LLM call runs. */
	public static boolean isSimulationEnabled() {
		return simulationEnabled;
	}

	/** Flips the master switch and saves it to the config so it survives restarts. */
	public static void setSimulationEnabled(boolean enabled) {
		simulationEnabled = enabled;
		if (config != null) {
			config.simulationEnabled = enabled;
			config.save();
		}
		LOGGER.info("AI Civilization simulation turned {}.", enabled ? "on" : "off");
	}

	private static void startObserver(int port, String token) {
		try {
			observerServer = new ObserverServer(port, token);
			observerServer.start();
			LOGGER.info("AI Civilization observer listening on port {}. Open http://<server address>:{}/?t={}",
					port, port, token);
		} catch (java.io.IOException | RuntimeException e) {
			observerServer = null;
			LOGGER.warn("AI Civilization observer could not start on port {}; continuing without it.", port, e);
		}
	}

	private static void stopObserver() {
		if (observerServer != null) {
			observerServer.stop();
			observerServer = null;
		}
	}

	private void onEndServerTick(net.minecraft.server.MinecraftServer server) {
		if (observerServer != null && server.getTickCount() % OBSERVER_INTERVAL_TICKS == 0) {
			try {
				observerServer.publish(snapshotCollector.collect(server));
			} catch (RuntimeException e) {
				LOGGER.warn("AI Civilization observer snapshot failed.", e);
			}
		}
		if (!simulationEnabled) {
			return;
		}
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry registry = PopulationRegistry.get(world);
			EventLog log = EventLog.get(world);
			long tick = world.getGameTime();
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
