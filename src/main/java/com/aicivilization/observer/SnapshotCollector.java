package com.aicivilization.observer;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.population.AgentChunkLoader;
import com.aicivilization.population.PopulationRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Builds an {@link ObserverSnapshot} from the overworld on the server
 * thread. Only the overworld is observed: each level has its own event log
 * with its own id sequence, and agents are spawned and live there.
 */
public final class SnapshotCollector {

	static final int RETAINED_EVENTS = 2000;
	private static final int CHRONICLE_LINES = 30;
	private static final int DETAIL_EVENT_LIMIT = 30;
	/** Stories on the page, and in the guest attribute (which is size-capped). */
	static final int STORY_LIMIT = 60;
	static final int RECENT_STORY_LIMIT = 15;
	private static final Set<EventType> NARRATIVE_TYPES = EnumSet.of(
			EventType.SPAWN, EventType.CONVERSATION, EventType.NEED_CRISIS, EventType.DEATH, EventType.ATTACKED,
				EventType.MILESTONE);

	private final String providerName;
	private final long reasoningIntervalTicks;
	private final BooleanSupplier simulationEnabled;
	private final BooleanSupplier withinActiveHours;
	private final String activeHours;
	private final Deque<SimEvent> retained = new ArrayDeque<>();
	private long lastEventId = 0;

	public SnapshotCollector(String providerName, long reasoningIntervalTicks, BooleanSupplier simulationEnabled,
			BooleanSupplier withinActiveHours, String activeHours) {
		this.providerName = providerName;
		this.reasoningIntervalTicks = reasoningIntervalTicks;
		this.simulationEnabled = simulationEnabled;
		this.withinActiveHours = withinActiveHours;
		this.activeHours = activeHours;
	}

	/** Must be called on the server thread. */
	public ObserverSnapshot collect(MinecraftServer server) {
		ServerLevel world = server.overworld();
		long tick = world.getGameTime();
		EventLog log = EventLog.get(world);
		for (SimEvent e : log.since(lastEventId)) {
			retained.addLast(e);
			lastEventId = e.id();
		}
		while (retained.size() > RETAINED_EVENTS) {
			retained.removeFirst();
		}

		Map<UUID, ObserverJson.Position> positions = new HashMap<>();
		String dimension = world.dimension().identifier().toString();
		for (AgentEntity body : world.getEntities(AICivilizationMod.AGENT_ENTITY_TYPE, e -> true)) {
			positions.put(body.getUUID(), new ObserverJson.Position(dimension, body.getX(), body.getY(), body.getZ()));
		}

		Map<UUID, String> names = new HashMap<>();
		Collection<AgentMind> allMinds = PopulationRegistry.get(world).population().allMinds();
		for (AgentMind mind : allMinds) {
			names.put(mind.identity().id(), mind.identity().name());
		}

		// One newest-first pass over the retained window, rather than scanning
		// the full event log once per agent every second.
		Map<UUID, List<SimEvent>> recentByAgent = new HashMap<>();
		var newestFirst = retained.descendingIterator();
		while (newestFirst.hasNext()) {
			SimEvent e = newestFirst.next();
			for (UUID subject : e.subjects()) {
				List<SimEvent> list = recentByAgent.computeIfAbsent(subject, k -> new ArrayList<>());
				if (list.size() < DETAIL_EVENT_LIMIT) {
					list.add(e);
				}
			}
		}

		JsonArray agents = new JsonArray();
		Map<String, String> details = new HashMap<>();
		int alive = 0;
		for (AgentMind mind : allMinds) {
			UUID id = mind.identity().id();
			ObserverJson.Position position = positions.get(id);
			agents.add(ObserverJson.agentSummary(mind, position, tick));
			details.put(id.toString(), ObserverJson.agentDetail(mind, position, tick,
					uuid -> names.getOrDefault(uuid, "unknown"), recentByAgent.getOrDefault(id, List.of())).toString());
			if (mind.isAlive()) {
				alive++;
			}
		}

		JsonObject overview = new JsonObject();
		overview.addProperty("ready", true);
		overview.addProperty("tick", tick);
		overview.addProperty("day", tick / 24000L);
		overview.addProperty("timeOfDay", world.getOverworldClockTime() % 24000L);
		overview.addProperty("alive", alive);
		overview.addProperty("dead", allMinds.size() - alive);
		overview.addProperty("total", allMinds.size());
		overview.addProperty("loadedBodies", positions.size());
		overview.addProperty("simulationEnabled", simulationEnabled.getAsBoolean());
		overview.addProperty("withinActiveHours", withinActiveHours.getAsBoolean());
		overview.addProperty("activeHours", activeHours);
		PopulationRegistry registry = PopulationRegistry.get(world);
		overview.addProperty("bodyScan", AgentChunkLoader.scanStatus());
		overview.addProperty("knownBodyChunks", registry.bodyChunks().size());
		overview.addProperty("forcedChunks", registry.forcedChunks().size());
		overview.addProperty("reasoningProvider", providerName);
		overview.addProperty("reasoningIntervalTicks", reasoningIntervalTicks);
		overview.addProperty("lastEventId", lastEventId);
		overview.addProperty("observedAtMillis", System.currentTimeMillis());
		overview.add("chronicle", chronicle());

		com.aicivilization.story.StoryGrouper stories = com.aicivilization.story.StoryLog.get(world).grouper();
		java.util.function.Function<UUID, String> nameOf = uuid -> names.getOrDefault(uuid, "unknown");
		String storiesJson = ObserverJson.stories(stories, log::byId, nameOf, STORY_LIMIT).toString();
		String recentStoriesJson = ObserverJson.stories(stories, log::byId, nameOf, RECENT_STORY_LIMIT, true).toString();

		return new ObserverSnapshot(tick, overview.toString(), agents.toString(), details,
				new ArrayList<>(retained), names, storiesJson, recentStoriesJson);
	}

	/** Latest narrative lines from the retained window, newest first. */
	private JsonArray chronicle() {
		JsonArray lines = new JsonArray();
		var it = retained.descendingIterator();
		while (it.hasNext() && lines.size() < CHRONICLE_LINES) {
			SimEvent e = it.next();
			if (NARRATIVE_TYPES.contains(e.type())) {
				JsonObject line = new JsonObject();
				line.addProperty("eventId", e.id());
				line.addProperty("day", e.tick() / 24000L);
				line.addProperty("text", e.summary());
				lines.add(line);
			}
		}
		return lines;
	}
}
