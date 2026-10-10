package com.aicivilization.story;

import com.aicivilization.events.Cause;
import com.aicivilization.events.CauseType;
import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sorts the event log into stories as events arrive. Pure logic with no
 * Minecraft types, so it is unit-tested.
 *
 * <ul>
 *   <li>Routine work (harvesting, eating, chopping, placing blocks) isn't a
 *   story: it's only counted, per agent per day.</li>
 *   <li>Bookkeeping (a reasoning pass starting, a failed call, a routine
 *   decision) is left out.</li>
 *   <li>Everything else joins the open story it was caused by, else the open
 *   story that most recently involved one of its people (its subjects, or an
 *   agent it names), else starts a new one.</li>
 *   <li>A story closes when nothing has happened in it for
 *   {@link #QUIET_TICKS}, or once it spans a game day or holds
 *   {@link #MAX_EVENTS} events, so a long-running thread continues as a new
 *   story rather than growing without end.</li>
 * </ul>
 */
public final class StoryGrouper {

	/** A quarter of a game day with nothing new and a story is over. */
	static final long QUIET_TICKS = 6000;
	static final long MAX_SPAN_TICKS = 24000;
	static final int MAX_EVENTS = 24;
	static final int MAX_STORIES = 300;
	static final int ROUTINE_DAYS = 14;
	static final long TICKS_PER_DAY = 24000;

	/** Matches the observer page's idea of routine work, plus block-by-block building and getting unstuck. */
	private static final Pattern ROUTINE = Pattern.compile("\\b(harvested|ate some|chopped|planted|tended|mined some"
			+ "|dug farmland|cut grass|made bread|cooked|hunted|fed two|picked|placed|put \\d|got stuck|climbed \\d+ blocks down|dug steps up"
			+ "|cut off by water|dug their way back|broke\\.)");
	/** Decisions worth telling: a plan agreed with someone, or setting out to make something. */
	private static final Pattern NOTABLE_DECISION = Pattern.compile(" means to | set out to make | wants to make ");

	enum Kind { STORY, ROUTINE, IGNORE }

	private final List<Story> stories = new ArrayList<>();
	/** Day -> agent -> routine jobs that day. */
	private final TreeMap<Long, Map<UUID, Integer>> routineByDay = new TreeMap<>();
	private long nextStoryId = 1;
	private long lastEventId = 0;

	static Kind classify(SimEvent e) {
		return switch (e.type()) {
			case REASONING_INVOKED, REASONING_FAILED, PERCEIVED -> Kind.IGNORE;
			case REASONING_RESULT -> e.summary().endsWith("nothing in particular this time") ? Kind.IGNORE : Kind.STORY;
			case DECISION -> NOTABLE_DECISION.matcher(e.summary()).find() ? Kind.STORY : Kind.IGNORE;
			case ACTION -> e.subjects().size() < 2 && ROUTINE.matcher(e.summary()).find() ? Kind.ROUTINE : Kind.STORY;
			default -> Kind.STORY;
		};
	}

	/**
	 * Takes in one event (in id order; ones already seen are skipped).
	 * {@code nameIndex} maps agents' names to ids, to spot agents an event
	 * mentions without them being a subject ("Mabry told Orrin: I agreed with
	 * Iris ...").
	 */
	public void accept(SimEvent e, Map<String, UUID> nameIndex) {
		if (e.id() <= lastEventId) {
			return;
		}
		lastEventId = e.id();
		closeQuiet(e.tick());
		Kind kind = classify(e);
		if (kind == Kind.IGNORE) {
			return;
		}
		if (kind == Kind.ROUTINE) {
			tally(e);
			return;
		}
		Set<UUID> involved = involved(e, nameIndex);
		Story story = causedBy(e).or(() -> sharing(involved)).orElse(null);
		if (story == null || full(story, e.tick())) {
			if (story != null) {
				story.close();
			}
			story = new Story(nextStoryId++, e.tick());
			stories.add(story);
			while (stories.size() > MAX_STORIES) {
				stories.remove(0);
			}
		}
		story.add(e.id(), e.type(), e.tick(), involved);
	}

	static Set<UUID> involved(SimEvent e, Map<String, UUID> nameIndex) {
		Set<UUID> out = new LinkedHashSet<>(e.subjects());
		for (String word : e.summary().split("[^\\p{L}\\p{N}_]+")) {
			UUID named = nameIndex.get(word);
			if (named != null) {
				out.add(named);
			}
		}
		return out;
	}

	private Optional<Story> causedBy(SimEvent e) {
		for (Cause cause : e.causes()) {
			if (cause.sourceType() != CauseType.EVENT) {
				continue;
			}
			long cited;
			try {
				cited = Long.parseLong(cause.sourceId());
			} catch (NumberFormatException ex) {
				continue;
			}
			for (int i = stories.size() - 1; i >= 0; i--) {
				Story s = stories.get(i);
				if (!s.closed() && s.eventIds().contains(cited)) {
					return Optional.of(s);
				}
			}
		}
		return Optional.empty();
	}

	private Optional<Story> sharing(Set<UUID> involved) {
		Story best = null;
		for (Story s : stories) {
			if (s.closed() || (best != null && s.lastTick() < best.lastTick())) {
				continue;
			}
			for (UUID id : s.people()) {
				if (involved.contains(id)) {
					best = s;
					break;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean full(Story s, long tick) {
		return s.eventIds().size() >= MAX_EVENTS || tick - s.firstTick() > MAX_SPAN_TICKS;
	}

	private void closeQuiet(long tick) {
		for (Story s : stories) {
			if (!s.closed() && tick - s.lastTick() > QUIET_TICKS) {
				s.close();
			}
		}
	}

	private void tally(SimEvent e) {
		long day = e.tick() / TICKS_PER_DAY;
		Map<UUID, Integer> day_ = routineByDay.computeIfAbsent(day, d -> new LinkedHashMap<>());
		for (UUID subject : e.subjects()) {
			day_.merge(subject, 1, Integer::sum);
		}
		while (routineByDay.size() > ROUTINE_DAYS) {
			routineByDay.pollFirstEntry();
		}
	}

	/** Closes stories that have gone quiet by {@code tick}, even with no new events arriving. */
	public void settle(long tick) {
		closeQuiet(tick);
	}

	/**
	 * The story to write up next, if any: one with events its write-up doesn't
	 * cover, that has finished (closed, quiet for {@code settleTicks}, or
	 * {@code batch} events behind), newest news first. Stories older than
	 * {@code maxAgeTicks} are left as their record, so a long backlog (the
	 * whole log, the first time) doesn't turn into hours of calls.
	 */
	public Optional<Story> nextToWrite(long tick, long settleTicks, int batch, long maxAgeTicks) {
		Story best = null;
		for (Story s : stories) {
			if (!s.needsWriting() || tick < s.nextAttemptTick() || tick - s.lastTick() > maxAgeTicks) {
				continue;
			}
			boolean ready = s.closed() || tick - s.lastTick() >= settleTicks
					|| s.eventIds().size() - s.writtenEvents() >= batch;
			if (ready && (best == null || s.lastTick() > best.lastTick())) {
				best = s;
			}
		}
		return Optional.ofNullable(best);
	}

	public List<Story> stories() {
		return List.copyOf(stories);
	}

	public Optional<Story> story(long id) {
		return stories.stream().filter(s -> s.id() == id).findFirst();
	}

	/** Routine jobs per agent, for each recent day (oldest first). */
	public Map<Long, Map<UUID, Integer>> routineByDay() {
		Map<Long, Map<UUID, Integer>> copy = new LinkedHashMap<>();
		routineByDay.forEach((day, counts) -> copy.put(day, Map.copyOf(counts)));
		return copy;
	}

	public long lastEventId() {
		return lastEventId;
	}

	// -- for StoryLog ---------------------------------------------------------

	long nextStoryId() {
		return nextStoryId;
	}

	void restore(List<Story> saved, Map<Long, Map<UUID, Integer>> routine, long nextStoryId, long lastEventId) {
		stories.clear();
		stories.addAll(saved);
		routineByDay.clear();
		routine.forEach((day, counts) -> routineByDay.put(day, new LinkedHashMap<>(counts)));
		this.nextStoryId = Math.max(nextStoryId, saved.stream().mapToLong(Story::id).max().orElse(0) + 1);
		this.lastEventId = lastEventId;
	}
}
