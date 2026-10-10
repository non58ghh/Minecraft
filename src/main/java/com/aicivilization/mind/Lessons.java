package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Its own failures, kept long enough to notice when the same one keeps
 * happening: looking for trees and finding none, coming back from a search
 * for food with nothing, getting stuck, being set on by monsters, going
 * where someone said something was and finding nothing. Each on its own is
 * just a bad day; the {@link #TIMES}th of a kind within {@link #WINDOW} is a
 * pattern, and it says so to itself, plainly: how often, over how long,
 * and what the times had in common (night or day, the same place). It says
 * nothing about what to do differently. Drawing that conclusion, or not,
 * is up to the agent.
 */
public final class Lessons {

	public enum Failure {
		NO_TREES("set out looking for trees and found none"),
		NO_FOOD("gone looking for food and come back with nothing"),
		STUCK("got stuck somewhere I couldn't get out of"),
		ATTACKED("been attacked by monsters"),
		MISLED("gone where I was told something was and found nothing there");

		final String phrase;

		Failure(String phrase) {
			this.phrase = phrase;
		}
	}

	/** One failure: what, where, when, and whether it was dark. */
	public record Miss(Failure kind, int x, int z, long tick, boolean night) {
	}

	static final long DAY = 24000;
	/** Failures this far back still count toward a pattern. */
	static final long WINDOW = 3 * DAY;
	/** This many of a kind within the window make a pattern. */
	static final int TIMES = 3;
	/** The same failure again this soon is the same failure, not another. */
	static final long MIN_APART = 1200;
	/** Having seen a pattern, it doesn't need telling again this soon. */
	static final long LESSON_GAP = 2 * DAY;
	/** All within this many blocks of each other: the same place. */
	static final int SAME_PLACE = 48;
	private static final int MAX_MISSES = 40;
	private static final String[] COUNTS = { "zero", "once", "twice", "three times", "four times", "five times", "six times",
			"seven times", "eight times", "nine times", "ten times" };

	private final List<Miss> misses = new ArrayList<>();
	private final Map<Failure, Long> lastLesson = new EnumMap<>(Failure.class);

	/**
	 * Notes a failure. Returns what it makes of it, if this one makes a
	 * pattern it hasn't noticed lately.
	 */
	public Optional<String> note(Failure kind, int x, int z, long tick, boolean night) {
		misses.removeIf(m -> tick - m.tick() > WINDOW);
		for (Miss m : misses) {
			if (m.kind() == kind && tick - m.tick() < MIN_APART) {
				return Optional.empty();
			}
		}
		misses.add(new Miss(kind, x, z, tick, night));
		while (misses.size() > MAX_MISSES) {
			misses.remove(0);
		}
		Long last = lastLesson.get(kind);
		if (last != null && tick - last < LESSON_GAP) {
			return Optional.empty();
		}
		List<Miss> same = misses.stream().filter(m -> m.kind() == kind).toList();
		if (same.size() < TIMES) {
			return Optional.empty();
		}
		lastLesson.put(kind, tick);
		return Optional.of(describe(kind, same, tick));
	}

	static String describe(Failure kind, List<Miss> same, long tick) {
		long days = Math.max(1, (tick - same.get(0).tick() + DAY - 1) / DAY);
		StringBuilder s = new StringBuilder("I've ").append(kind.phrase).append(' ')
				.append(COUNTS[Math.min(same.size(), COUNTS.length - 1)])
				.append(days == 1 ? " in the last day" : " in the last " + days + " days");
		boolean allNight = same.stream().allMatch(Miss::night);
		boolean allDay = same.stream().noneMatch(Miss::night);
		boolean samePlace = same.stream().allMatch(a -> same.stream().allMatch(b -> {
			long dx = a.x() - b.x(), dz = a.z() - b.z();
			return dx * dx + dz * dz <= (long) SAME_PLACE * SAME_PLACE;
		}));
		if (allNight) {
			s.append(", every time at night");
		} else if (allDay) {
			s.append(", every time by day");
		}
		if (samePlace) {
			s.append(allNight || allDay ? ", and" : ",").append(" always around the same place");
		}
		return s.append('.').toString();
	}

	/** For saving. */
	public List<Miss> misses() {
		return List.copyOf(misses);
	}

	public Map<Failure, Long> lastLessons() {
		return Map.copyOf(lastLesson);
	}

	public void restore(List<Miss> savedMisses, Map<Failure, Long> savedLessons) {
		misses.clear();
		misses.addAll(savedMisses);
		lastLesson.clear();
		lastLesson.putAll(savedLessons);
	}
}
