package com.aicivilization;

import com.aicivilization.behavior.ConversationBehavior;
import com.aicivilization.command.CivCommands;
import com.aicivilization.config.ActiveHours;
import com.aicivilization.config.ModConfig;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.observer.GuestAttributePublisher;
import com.aicivilization.observer.ObserverServer;
import com.aicivilization.observer.ObserverSnapshot;
import com.aicivilization.observer.SnapshotCollector;
import com.aicivilization.population.AgentChunkLoader;
import com.aicivilization.population.EventExplainer;
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

import java.time.Instant;
import java.util.random.RandomGenerator;

public final class AICivilizationMod implements ModInitializer {

	public static final String MOD_ID = "aicivilization";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String[] NAME_POOL = {
			"Elias", "Marcus", "Iris", "Talia", "Osric", "Nadia", "Petra", "Corwin",
			"Sable", "Rowan", "Idris", "Lyra", "Bram", "Thessaly", "Osgood", "Wren",
			"Aldous", "Brenna", "Cassia", "Dorian", "Edda", "Fenwick", "Greta", "Hollis",
			"Ilse", "Jorah", "Kestrel", "Linnea", "Mabry", "Nell", "Orrin", "Perrin",
			"Quill", "Rhea", "Silas", "Tamsin", "Ulric", "Vesna", "Wilder", "Yara"
	};

	public static EntityType<AgentEntity> AGENT_ENTITY_TYPE;

	private static ReasoningScheduler reasoningScheduler;
	private static com.aicivilization.story.StoryWriter storyWriter;

	/** Ticks between observer snapshots (one second). */
	private static final int OBSERVER_INTERVAL_TICKS = 20;

	private static ModConfig config;
	private static volatile boolean simulationEnabled = true;
	private static ActiveHours activeHours = ActiveHours.always();
	private static volatile boolean withinActiveHours = true;

	private static ObserverServer observerServer;
	/** Once a minute: copy observer data to the VM's guest attributes (see GuestAttributePublisher). */
	private static final int GUEST_ATTRIBUTE_INTERVAL_TICKS = 1200;
	private static GuestAttributePublisher guestAttributes;
	private static SnapshotCollector snapshotCollector;

	@Override
	public void onInitialize() {
		ModConfig config = ModConfig.loadOrCreate();
		AICivilizationMod.config = config;
		simulationEnabled = config.simulationEnabled;
		try {
			activeHours = ActiveHours.parse(config.activeHoursStart, config.activeHoursEnd, config.activeHoursTimeZone);
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Ignoring invalid active hours in aicivilization.json; agents will run all day.", e);
		}
		withinActiveHours = activeHours.isActive(Instant.now());

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
		reasoningScheduler = new ReasoningScheduler(provider, config.reasoningIntervalTicks,
				config.reasoningCrisisCooldownTicks, config.reasoningNoveltyThreshold,
				config.maxReasoningCallsPerAgentPerDay);

		storyWriter = new com.aicivilization.story.StoryWriter(provider, config.storyIntervalTicks,
				AICivilizationMod::isSimulationEnabled);

		if (config.monstersHuntAgents) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD
					.register(com.aicivilization.entity.MonsterHunting::onLoad);
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) -> CivCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		ServerLifecycleEvents.SERVER_STARTED.register(AgentChunkLoader::locateUnknownBodies);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> foundFirstSettlement(server, config));
		// The game's recipes, read once they're loaded (and again whenever datapacks reload).
		ServerLifecycleEvents.SERVER_STARTED.register(com.aicivilization.world.RecipeCatalog::rebuild);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) {
				com.aicivilization.world.RecipeCatalog.rebuild(server);
			}
		});
		// Conversations worth writing down are written by the same LLM, on the server thread when they come back.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ConversationBehavior.useWriter(provider, server,
				config.dialogueIntervalTicks));

		if (config.observerEnabled) {
			if (config.publishToGuestAttributes) {
				guestAttributes = new GuestAttributePublisher();
			}
			snapshotCollector = new SnapshotCollector(config.llmProvider, config.reasoningIntervalTicks,
					AICivilizationMod::isSimulationEnabled, AICivilizationMod::isWithinActiveHours,
					activeHours.describe());
			ServerLifecycleEvents.SERVER_STARTED.register(server -> startObserver(config.observerPort,
					config.observerRequireToken ? config.observerToken : ""));
			ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopObserver());
		}

		LOGGER.info("AI Civilization initialized (llmProvider={}, simulation {}, active hours {}).",
				config.llmProvider, simulationEnabled ? "on" : "off", activeHours.describe());
	}

	/** False after {@code /civ off}: agents stand still and no reasoning or LLM call runs. */
	public static boolean isSimulationEnabled() {
		return simulationEnabled;
	}

	public static long starvationIntervalTicks() {
		return config == null ? 0 : config.starvationDamageIntervalTicks;
	}

	/** Whether the wall clock is inside the configured active hours (always true if none are set). */
	public static boolean isWithinActiveHours() {
		return withinActiveHours;
	}

	public static String activeHoursDescription() {
		return activeHours.describe();
	}

	/** Whether agents act and think right now: switched on and within active hours. */
	public static boolean isSimulationRunning() {
		return simulationEnabled && withinActiveHours;
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
			if (token.isEmpty()) {
				LOGGER.info("AI Civilization observer listening on port {} with no token required. "
						+ "Open http://<server address>:{}/", port, port);
			} else {
				LOGGER.info("AI Civilization observer listening on port {}. Open http://<server address>:{}/?t={}",
						port, port, token);
			}
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
		if (server.getTickCount() % com.aicivilization.story.StoryWriter.CHECK_EVERY_TICKS == 0) {
			try {
				ServerLevel overworld = server.overworld();
				storyWriter.tick(overworld, overworld.getGameTime(), server);
			} catch (RuntimeException e) {
				LOGGER.warn("AI Civilization story update failed.", e);
			}
		}
		if (observerServer != null && server.getTickCount() % OBSERVER_INTERVAL_TICKS == 0) {
			try {
				ObserverSnapshot snapshot = snapshotCollector.collect(server);
				observerServer.publish(snapshot);
				if (guestAttributes != null && server.getTickCount() % GUEST_ATTRIBUTE_INTERVAL_TICKS == 0) {
					guestAttributes.publish(snapshot);
				}
			} catch (RuntimeException e) {
				LOGGER.warn("AI Civilization observer snapshot failed.", e);
			}
		}
		if (server.getTickCount() % OBSERVER_INTERVAL_TICKS == 0) {
			updateActiveHours();
			for (ServerLevel world : server.getAllLevels()) {
				AgentChunkLoader.update(world, isSimulationRunning());
			}
		}
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry explaining = PopulationRegistry.get(world);
			EventLog.get(world).explainWith(id -> EventExplainer.why(explaining.population(), id));
		}
		if (!isSimulationRunning()) {
			return;
		}
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry registry = PopulationRegistry.get(world);
			EventLog log = EventLog.get(world);
			long tick = world.getGameTime();
			for (AgentMind mind : registry.population().allMinds()) {
				// Dormant minds (no loaded body) can't act on a new goal, so they don't think.
				net.minecraft.world.entity.Entity body = mind.isAlive() ? world.getEntity(mind.identity().id()) : null;
				if (body != null) {
					reasoningScheduler.maybeInvoke(mind, tick, log, server, () -> situation(world, body, mind, registry),
							other -> registry.population().getMind(other).map(m -> m.identity().name()).orElse(null));
					reasoningScheduler.maybeDesign(mind, tick, log, server);
				}
			}
		}
	}

	private static void updateActiveHours() {
		boolean within = activeHours.isActive(Instant.now());
		if (within != withinActiveHours) {
			withinActiveHours = within;
			LOGGER.info(within
					? "AI Civilization active hours ({}) started: agents are acting and thinking."
					: "AI Civilization active hours ({}) ended: agents are resting and no AI calls are made.",
					activeHours.describe());
		}
	}

	/** A name nobody (living or dead) has had yet: a free one from the pool, else a pool name with a number. */
	public static String randomAgentName(RandomGenerator rng, java.util.Set<String> taken) {
		java.util.List<String> free = new java.util.ArrayList<>();
		for (String name : NAME_POOL) {
			if (!taken.contains(name)) {
				free.add(name);
			}
		}
		if (!free.isEmpty()) {
			return free.get(rng.nextInt(free.size()));
		}
		for (int n = 2; ; n++) {
			// No space, so /civ inspect still takes it as one word.
			String name = NAME_POOL[rng.nextInt(NAME_POOL.length)] + n;
			if (!taken.contains(name)) {
				return name;
			}
		}
	}

	/** Spawns the founders in a world that has never had an agent; see {@link ModConfig#foundingAgents}. */
	private static void foundFirstSettlement(net.minecraft.server.MinecraftServer server, ModConfig config) {
		net.minecraft.server.level.ServerLevel world = server.overworld();
		int wanted = com.aicivilization.population.Founding.foundersToSpawn(config.foundingAgents,
				com.aicivilization.population.PopulationRegistry.get(world).population().size(), config.maxAgents);
		if (wanted > 0) {
			int founded = com.aicivilization.population.AgentBodies.found(world, wanted);
			LOGGER.info("AI Civilization: founded the first settlement with {} agents at the world spawn", founded);
		}
	}

	/** How far around an agent notices who's there when it stops to think. */
	private static final double SIGHT = 16;

	/** What an agent perceives where it stands, for its thinking: the time, where it is, who's in sight. */
	private static com.aicivilization.reasoning.AgentContext.Situation situation(ServerLevel world,
			net.minecraft.world.entity.Entity body, AgentMind mind, PopulationRegistry registry) {
		long day = world.getGameTime() / 24000L;
		long timeOfDay = world.getOverworldClockTime() % 24000L;
		net.minecraft.core.BlockPos at = body.blockPosition();
		String place = mind.home().map(h -> {
			long d = Math.round(Math.sqrt(at.distSqr(new net.minecraft.core.BlockPos(h.x(), h.y(), h.z()))));
			return d <= 6 ? "At your home." : d <= 32 ? "Near your home, about " + d + " blocks away."
					: "About " + d + " blocks from your home.";
		}).orElse("Out in the open.");
		java.util.List<String> inSight = new java.util.ArrayList<>();
		net.minecraft.world.phys.AABB box = body.getBoundingBox().inflate(SIGHT);
		for (var other : world.getEntities(AGENT_ENTITY_TYPE, box, e -> e != body && e.isAlive())) {
			registry.population().getMind(other.getUUID()).ifPresent(m -> inSight.add(m.identity().name()));
		}
		for (var player : world.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box, p -> !p.isSpectator())) {
			inSight.add(player.getName().getString() + " (a player)");
		}
		int living = (int) registry.population().allMinds().stream().filter(AgentMind::isAlive).count();
		return new com.aicivilization.reasoning.AgentContext.Situation(day, timeOfDay, place, inSight, living);
	}
}
