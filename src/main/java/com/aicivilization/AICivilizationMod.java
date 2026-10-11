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
	/** What the LLM has cost this month, when one is used; null otherwise. */
	private static com.aicivilization.reasoning.SpendLedger spendLedger;

	public static com.aicivilization.reasoning.SpendLedger spendLedger() {
		return spendLedger;
	}
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
	/** The public copy of the observer page, when one is set up (see PublicSitePublisher). */
	private static com.aicivilization.observer.PublicSitePublisher publicSite;
	/** Who is Guardian of whom, read from Stripe (see Guardians); null when not set up. */
	private static com.aicivilization.observer.Guardians guardians;
	/** The (budgeted) LLM provider everything writes with. */
	private static ReasoningProvider writer;

	public static com.aicivilization.observer.Guardians guardians() {
		return guardians;
	}

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

		boolean anthropic = "anthropic".equalsIgnoreCase(config.llmProvider);
		boolean vertex = "vertex".equalsIgnoreCase(config.llmProvider);
		boolean paid = anthropic || vertex;
		// Only real calls cost anything: the heuristic provider runs unmetered.
		com.aicivilization.reasoning.CallBudget budget = paid
				? new com.aicivilization.reasoning.CallBudget(config.maxApiCallsPerHour)
				: com.aicivilization.reasoning.CallBudget.unlimited();
		com.aicivilization.reasoning.SpendLedger ledger = null;
		if (paid) {
			ledger = new com.aicivilization.reasoning.SpendLedger(config.monthlyBudgetUsd,
					net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aicivilization-spend.json"));
			budget.useLedger(ledger);
			spendLedger = ledger;
			String fast = vertex ? config.vertexFastModel : config.anthropicModel;
			String strong = vertex ? config.vertexStrongModel
					: config.anthropicStrongModel.isBlank() ? config.anthropicModel : config.anthropicStrongModel;
			LOGGER.info("LLM spend this month so far: ${} of ${} ({} for minds, {} for what people read).",
					String.format(java.util.Locale.ROOT, "%.2f", ledger.spentThisMonth()), config.monthlyBudgetUsd, fast, strong);
		}
		ReasoningProvider inner;
		if (vertex) {
			String project = config.vertexProject.isBlank() ? new com.aicivilization.reasoning.GoogleCredentials().projectId()
					: config.vertexProject.strip();
			if (project.isEmpty()) {
				LOGGER.warn("llmProvider is vertex but no Google Cloud project is set or found; using heuristic reasoning.");
				inner = new HeuristicReasoningProvider();
			} else {
				inner = new com.aicivilization.reasoning.VertexGeminiProvider(project, config.vertexLocation.strip(),
						config.vertexFastModel.strip(), config.vertexStrongModel.strip(), config.vertexContextCaching,
						config.vertexCacheTtlSeconds, ledger);
			}
		} else if (anthropic) {
			inner = new AnthropicReasoningProvider(config.resolveAnthropicApiKey(), config.anthropicModel,
					config.anthropicStrongModel, config.anthropicMaxTokens, ledger);
		} else {
			inner = new HeuristicReasoningProvider();
		}
		ReasoningProvider provider = new com.aicivilization.reasoning.BudgetedProvider(inner, budget);
		writer = provider;
		reasoningScheduler = new ReasoningScheduler(provider, config.reasoningIntervalTicks,
				config.reasoningCrisisCooldownTicks, config.reasoningNoveltyThreshold,
				config.maxReasoningCallsPerAgentPerDay, budget);

		storyWriter = new com.aicivilization.story.StoryWriter(provider, config.storyIntervalTicks,
				AICivilizationMod::isSimulationEnabled);

		if (config.monstersHuntAgents) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD
					.register(com.aicivilization.entity.MonsterHunting::onLoad);
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) -> CivCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		// After the server stops (a crash included), the process should end so the service can restart it.
		// If anything keeps it alive past a minute, end it.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Thread reaper = new Thread(() -> {
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					return;
				}
				LOGGER.warn("AI Civilization: the server stopped but the process didn't exit; ending it.");
				Runtime.getRuntime().halt(1);
			}, "aicivilization-exit");
			reaper.setDaemon(true);
			reaper.start();
		});
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
		// Players can speak to agents in chat; the nearest (or one named) answers.
		com.aicivilization.behavior.PlayerChat.useWriter(provider);
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register(
				com.aicivilization.behavior.PlayerChat::onChat);

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
			publicSite = publicSite(config);
			if (config.guardianPaymentLink != null && !config.guardianPaymentLink.isBlank()) {
				guardians = new com.aicivilization.observer.Guardians(config.guardianPaymentLink.strip(),
						() -> instanceSecret(config.stripeKeyEnv, "aiciv-stripe-key"), config.guardianEverySeconds);
				LOGGER.info("Reading Guardians for payment link {} from Stripe every {}s.", config.guardianPaymentLink.strip(),
						config.guardianEverySeconds);
			}
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

	/** The public copy of the page, if it's switched on and has a bucket; null otherwise. */
	private static com.aicivilization.observer.PublicSitePublisher publicSite(ModConfig config) {
		if (!config.publicSiteEnabled) {
			return null;
		}
		if (config.publicSiteBucket == null || config.publicSiteBucket.isBlank()) {
			LOGGER.warn("Public site is on but no publicSiteBucket is set; not publishing.");
			return null;
		}
		LOGGER.info("Publishing a public copy of the observer page to gs://{} every {}s.", config.publicSiteBucket.strip(),
				config.publicSiteEverySeconds);
		return new com.aicivilization.observer.PublicSitePublisher(config.publicSiteBucket.strip(),
				new com.aicivilization.reasoning.GoogleCredentials()::accessToken, config.publicSiteEverySeconds);
	}

	/** A secret from the named environment variable, else from this VM's instance metadata on Google Compute Engine. */
	private static String instanceSecret(String envName, String metadataKey) {
		String fromEnv = envName == null || envName.isBlank() ? null : System.getenv(envName);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.strip();
		}
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
					.proxy(java.net.http.HttpClient.Builder.NO_PROXY)
					.connectTimeout(java.time.Duration.ofSeconds(2))
					.build();
			java.net.http.HttpResponse<String> response = client.send(java.net.http.HttpRequest.newBuilder(
							java.net.URI.create("http://metadata.google.internal/computeMetadata/v1/instance/attributes/" + metadataKey))
					.timeout(java.time.Duration.ofSeconds(3))
					.header("Metadata-Flavor", "Google")
					.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 ? response.body().strip() : "";
		} catch (java.io.IOException | RuntimeException e) {
			return "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
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
				if (guardians != null) {
					guardians.maybePoll();
				}
				ObserverSnapshot snapshot = snapshotCollector.collect(server);
				observerServer.publish(snapshot);
				if (guestAttributes != null && server.getTickCount() % GUEST_ATTRIBUTE_INTERVAL_TICKS == 0) {
					guestAttributes.publish(snapshot);
				}
				if (publicSite != null) {
					publicSite.publish(snapshot);
				}
			} catch (RuntimeException e) {
				LOGGER.warn("AI Civilization observer snapshot failed.", e);
			}
		}
		if (server.getTickCount() % OBSERVER_INTERVAL_TICKS == 0) {
			updateActiveHours();
			for (ServerLevel world : server.getAllLevels()) {
				AgentChunkLoader.update(world, isSimulationRunning());
				if (config.monstersHuntAgents && isSimulationRunning()) {
					// Monsters come to agents as they come to players (vanilla only spawns them near players).
					com.aicivilization.entity.AgentMobSpawner.tick(world);
				}
			}
		}
		if (server.getTickCount() % FOUNDER_EVERY_TICKS == FOUNDER_EVERY_TICKS / 2) {
			ServerLevel overworld = server.overworld();
			PopulationRegistry founding = PopulationRegistry.get(overworld);
			if (founding.foundersPending() > 0 && com.aicivilization.population.AgentBodies.foundOne(overworld,
					config.foundingSpread, config.foundingApart)) {
				founding.founderPlaced();
				if (founding.foundersPending() == 0) {
					LOGGER.info("AI Civilization: all founders placed");
				}
			}
		}
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry explaining = PopulationRegistry.get(world);
			EventLog.get(world).explainWith(id -> EventExplainer.why(explaining.population(), id));
		}
		if (!isSimulationRunning()) {
			return;
		}
		if (config.wanderers && server.getTickCount() % com.aicivilization.population.Wanderers.CHECK_EVERY_TICKS == 0) {
			com.aicivilization.population.Wanderers.tick(server.overworld(), config.maxAgents);
		}
		if (guardians != null && server.getTickCount() % com.aicivilization.population.Wanderers.CHECK_EVERY_TICKS == 300) {
			com.aicivilization.population.SupplyDrops.tick(server.overworld(), guardians);
		}
		if (guardians != null && server.getTickCount() % com.aicivilization.story.Journals.CHECK_EVERY_TICKS == 900) {
			com.aicivilization.story.Journals.tick(server.overworld(), guardians, writer,
					Math.max(1, config.guardianJournalEveryHours) * 3_600_000L, server);
		}
		if (config.children && server.getTickCount() % com.aicivilization.population.Wanderers.CHECK_EVERY_TICKS == 600) {
			com.aicivilization.population.Births.tick(server.overworld(), config.maxAgents);
		}
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry registry = PopulationRegistry.get(world);
			EventLog log = EventLog.get(world);
			long tick = world.getGameTime();
			for (AgentMind mind : registry.population().allMinds()) {
				// Dormant minds (no loaded body) can't act on a new goal, so they don't think.
				net.minecraft.world.entity.Entity body = mind.isAlive() ? world.getEntity(mind.identity().id()) : null;
				// Children run on instinct alone: no paid thinking until they're grown.
				if (body != null && !mind.isChild(tick)) {
					long timeOfDay = world.getOverworldClockTime() % 24000L;
					reasoningScheduler.maybeInvoke(mind, tick, log, server, () -> situation(world, body, mind, registry),
							other -> registry.population().getMind(other).map(m -> m.identity().name()).orElse(null),
							timeOfDay >= 12500 && timeOfDay < 23500);
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
		com.aicivilization.population.PopulationRegistry registry = com.aicivilization.population.PopulationRegistry.get(world);
		int wanted = com.aicivilization.population.Founding.foundersToSpawn(config.foundingAgents,
				registry.population().size(), config.maxAgents);
		if (wanted <= 0 || registry.foundingStarted()) {
			return;
		}
		if (config.foundingSpread <= 0) {
			registry.startFounding(0);
			int founded = com.aicivilization.population.AgentBodies.foundTogether(world, wanted);
			LOGGER.info("AI Civilization: founded the first settlement with {} agents at the world spawn", founded);
		} else {
			// Scattered far apart, one every couple of seconds: each may mean generating new land.
			registry.startFounding(wanted);
			LOGGER.info("AI Civilization: founding {} agents scattered within {} blocks of the spawn", wanted,
					config.foundingSpread);
		}
	}

	/** A scattered founding places one founder (trying up to three spots) this often. */
	private static final int FOUNDER_EVERY_TICKS = 20;

	/** How far around an agent notices who's there when it stops to think. */
	private static final double SIGHT = 32;

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
		double health = body instanceof net.minecraft.world.entity.LivingEntity l && l.getMaxHealth() > 0
				? l.getHealth() / l.getMaxHealth() : -1;
		// Monsters it can see, by kind: "2 zombies", "a spider close by".
		java.util.Map<String, int[]> seen = new java.util.TreeMap<>();
		for (var m : world.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box,
				m -> m.isAlive() && (body.distanceToSqr(m) <= 64 || body instanceof net.minecraft.world.entity.LivingEntity l2
						&& l2.hasLineOfSight(m)))) {
			int[] c = seen.computeIfAbsent(m.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), k -> new int[2]);
			c[0]++;
			if (body.distanceToSqr(m) <= 64) {
				c[1]++;
			}
		}
		java.util.List<String> threats = new java.util.ArrayList<>();
		seen.forEach((kind, c) -> threats.add((c[0] == 1 ? (kind.matches("^[aeiou].*") ? "an " : "a ") + kind : c[0] + " " + kind + "s")
				+ (c[1] > 0 ? " close by" : "")));
		return new com.aicivilization.reasoning.AgentContext.Situation(day, timeOfDay, place, inSight, living, health, threats);
	}
}
