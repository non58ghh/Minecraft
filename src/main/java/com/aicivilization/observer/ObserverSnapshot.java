package com.aicivilization.observer;

import com.aicivilization.events.SimEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable copy of what the observer page shows, built on the server
 * thread and then only read by HTTP threads. JSON is pre-rendered for the
 * views that need mutable mind state; events are kept as (immutable)
 * records so the timeline can be filtered per request.
 *
 * @param events retained recent events, oldest first
 * @param names  display name for every agent id in the population
 */
public record ObserverSnapshot(
		long tick,
		String overviewJson,
		String agentsJson,
		Map<String, String> agentDetailJson,
		List<SimEvent> events,
		Map<UUID, String> names,
		/** The chronicle's stories as JSON (see ObserverJson#stories). */
		String storiesJson,
		/** The newest few stories, small enough for a guest attribute. */
		String recentStoriesJson,
		/** The land around the agents, for the map (see TerrainMap); "{}" until drawn. */
		String terrainJson,
		/** Each agent's last few days (see DigestBook). */
		String digestJson,
		/** Alerts standing now: starving, not moved, deaths. */
		String alertsJson
) {
	public ObserverSnapshot(long tick, String overviewJson, String agentsJson, Map<String, String> agentDetailJson,
			List<SimEvent> events, Map<UUID, String> names) {
		this(tick, overviewJson, agentsJson, agentDetailJson, events, names, EMPTY_STORIES, EMPTY_STORIES, "{}",
				"{\"agents\":[]}", "{\"alerts\":[]}");
	}

	static final String EMPTY_STORIES = "{\"stories\":[],\"routine\":[]}";

	public ObserverSnapshot {
		agentDetailJson = Map.copyOf(agentDetailJson);
		events = List.copyOf(events);
		names = Map.copyOf(names);
	}

	public static ObserverSnapshot empty() {
		return new ObserverSnapshot(0, "{\"ready\":false}", "[]", Map.of(), List.of(), Map.of());
	}

	public String nameOf(UUID id) {
		return names.getOrDefault(id, "unknown");
	}
}
