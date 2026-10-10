package com.aicivilization.story;

import com.aicivilization.events.EventType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One story in the chronicle: notable events that belong together (the same
 * people, a cause and what came of it), plus the write-up of them once one
 * has been made. Plain data with no Minecraft types; {@link StoryLog}
 * persists it and {@link StoryWriter} fills in the write-up.
 */
public final class Story {

	private final long id;
	private final List<Long> eventIds = new ArrayList<>();
	private final Set<EventType> types = EnumSet.noneOf(EventType.class);
	/** Agents involved: subjects of its events and agents named in them, in order of appearance. */
	private final Set<UUID> people = new LinkedHashSet<>();
	private long firstTick;
	private long lastTick;
	private boolean closed;

	private String headline = "";
	private String text = "";
	private String stands = "";
	/** How many of its events the current write-up covers (0 = not written). */
	private int writtenEvents;
	/** Not before this tick: set after a failed write, so one story can't hog the writer. */
	private long nextAttemptTick;

	Story(long id, long firstTick) {
		this.id = id;
		this.firstTick = firstTick;
		this.lastTick = firstTick;
	}

	void add(long eventId, EventType type, long tick, Set<UUID> involved) {
		eventIds.add(eventId);
		types.add(type);
		people.addAll(involved);
		lastTick = Math.max(lastTick, tick);
	}

	void close() {
		closed = true;
	}

	/** One thought or one piece of news on its own is a line, not a story; anything more gets written up. */
	public boolean worthWriting() {
		return eventIds.size() >= 2 || types.contains(EventType.CONVERSATION) || types.contains(EventType.MILESTONE)
				|| types.contains(EventType.DEATH) || types.contains(EventType.ATTACKED) || types.contains(EventType.SPAWN)
				|| types.contains(EventType.WROTE);
	}

	/** Has events its write-up doesn't cover yet. */
	public boolean needsWriting() {
		return worthWriting() && eventIds.size() > writtenEvents;
	}

	/** The write-up, covering the first {@code coveredEvents} events. */
	public void written(String headline, String text, String stands, int coveredEvents) {
		this.headline = headline;
		this.text = text;
		this.stands = stands;
		this.writtenEvents = Math.min(coveredEvents, eventIds.size());
	}

	public void retryAfter(long tick) {
		this.nextAttemptTick = tick;
	}

	public long id() {
		return id;
	}

	public List<Long> eventIds() {
		return List.copyOf(eventIds);
	}

	public Set<EventType> types() {
		return Set.copyOf(types);
	}

	public List<UUID> people() {
		return List.copyOf(people);
	}

	public long firstTick() {
		return firstTick;
	}

	public long lastTick() {
		return lastTick;
	}

	public boolean closed() {
		return closed;
	}

	public String headline() {
		return headline;
	}

	public String text() {
		return text;
	}

	public String stands() {
		return stands;
	}

	public int writtenEvents() {
		return writtenEvents;
	}

	public long nextAttemptTick() {
		return nextAttemptTick;
	}

	/** For {@link StoryLog} when loading a saved story. */
	static Story restore(long id, List<Long> eventIds, Set<EventType> types, List<UUID> people, long firstTick, long lastTick,
			boolean closed, String headline, String text, String stands, int writtenEvents) {
		Story s = new Story(id, firstTick);
		s.eventIds.addAll(eventIds);
		s.types.addAll(types);
		s.people.addAll(people);
		s.lastTick = lastTick;
		s.closed = closed;
		s.headline = headline;
		s.text = text;
		s.stands = stands;
		s.writtenEvents = writtenEvents;
		return s;
	}
}
