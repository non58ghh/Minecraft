package com.aicivilization.events;

import java.util.List;
import java.util.UUID;

/**
 * A structured, machine-readable record of something that happened in the
 * simulation, sufficient to reconstruct <em>why</em> after the fact. This is
 * the backbone for {@code /civ why}: unlike {@code WorldChronicle} (prose
 * for humans), every field here is structured and every cause is typed.
 *
 * <p>Plain data holder — no Minecraft dependency. Only {@link EventLog}
 * (in this same package) knows how to persist these via Minecraft's
 * {@code SavedData}.
 */
public record SimEvent(
		long id,
		long tick,
		EventType type,
		List<UUID> subjects,
		String summary,
		List<Cause> causes,
		/** What was said, line by line ("Iris: ..."), for a conversation; empty otherwise. */
		List<String> transcript
) {
	public SimEvent {
		subjects = List.copyOf(subjects);
		causes = List.copyOf(causes);
		transcript = List.copyOf(transcript);
	}

	public SimEvent(long id, long tick, EventType type, List<UUID> subjects, String summary, List<Cause> causes) {
		this(id, tick, type, subjects, summary, causes, List.of());
	}
}
