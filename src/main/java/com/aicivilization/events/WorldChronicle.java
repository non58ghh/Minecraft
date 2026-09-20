package com.aicivilization.events;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A human-readable narrative <em>derived from</em> the {@link EventLog} —
 * not a separate hand-authored log. Only events of narratively-significant
 * types are surfaced; everything else stays in the structured log for
 * {@code /civ why}.
 */
public final class WorldChronicle {

	private static final Set<EventType> NARRATIVE_TYPES = EnumSet.of(
			EventType.SPAWN, EventType.CONVERSATION, EventType.NEED_CRISIS, EventType.DEATH);

	private WorldChronicle() {
	}

	public static List<String> render(EventLog log) {
		return log.all().stream()
				.filter(e -> NARRATIVE_TYPES.contains(e.type()))
				.map(e -> "Day " + (e.tick() / 24000L) + ": " + e.summary())
				.toList();
	}
}
