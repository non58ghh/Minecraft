package com.aicivilization.config;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A daily wall-clock window during which agents act, e.g. 18:00–00:00 in
 * America/New_York. The window may cross midnight; an end of 00:00 means
 * midnight. Pure logic with no Minecraft types, so it is unit-testable.
 */
public final class ActiveHours {

	private static final ActiveHours ALWAYS = new ActiveHours(null, null, null);

	private final LocalTime start;
	private final LocalTime end;
	private final ZoneId zone;

	private ActiveHours(LocalTime start, LocalTime end, ZoneId zone) {
		this.start = start;
		this.end = end;
		this.zone = zone;
	}

	public static ActiveHours always() {
		return ALWAYS;
	}

	/**
	 * Parses {@code HH:mm} times and an IANA zone id. Blank start or end, or
	 * start equal to end, means no restriction.
	 *
	 * @throws IllegalArgumentException if a value can't be parsed
	 */
	public static ActiveHours parse(String start, String end, String zone) {
		if (start == null || start.isBlank() || end == null || end.isBlank()) {
			return ALWAYS;
		}
		try {
			LocalTime s = LocalTime.parse(start.trim());
			LocalTime e = LocalTime.parse(end.trim());
			if (s.equals(e)) {
				return ALWAYS;
			}
			ZoneId z = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
			return new ActiveHours(s, e, z);
		} catch (DateTimeException ex) {
			throw new IllegalArgumentException("Bad active hours " + start + "–" + end + " " + zone, ex);
		}
	}

	public boolean isRestricted() {
		return start != null;
	}

	public boolean isActive(Instant now) {
		if (!isRestricted()) {
			return true;
		}
		LocalTime t = LocalTime.ofInstant(now, zone);
		if (start.isBefore(end)) {
			return !t.isBefore(start) && t.isBefore(end);
		}
		// Crosses midnight (including an end of 00:00).
		return !t.isBefore(start) || t.isBefore(end);
	}

	/** E.g. "18:00–00:00 America/New_York", or "all day". */
	public String describe() {
		return isRestricted() ? start + "–" + end + " " + zone.getId() : "all day";
	}
}
