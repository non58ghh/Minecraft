package com.aicivilization.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveHoursTest {

	private static final ZoneId NY = ZoneId.of("America/New_York");

	private static Instant ny(int hour, int minute) {
		return LocalDateTime.of(2026, 10, 6, hour, minute).atZone(NY).toInstant();
	}

	@Test
	void eveningWindowEndingAtMidnight() {
		ActiveHours hours = ActiveHours.parse("18:00", "00:00", "America/New_York");
		assertFalse(hours.isActive(ny(17, 59)));
		assertTrue(hours.isActive(ny(18, 0)));
		assertTrue(hours.isActive(ny(23, 59)));
		assertFalse(hours.isActive(ny(0, 0)));
		assertFalse(hours.isActive(ny(12, 0)));
		assertEquals("18:00–00:00 America/New_York", hours.describe());
	}

	@Test
	void sameDayWindow() {
		ActiveHours hours = ActiveHours.parse("08:00", "14:00", "America/New_York");
		assertFalse(hours.isActive(ny(7, 59)));
		assertTrue(hours.isActive(ny(8, 0)));
		assertFalse(hours.isActive(ny(14, 0)));
	}

	@Test
	void windowAcrossMidnight() {
		ActiveHours hours = ActiveHours.parse("22:00", "04:00", "America/New_York");
		assertTrue(hours.isActive(ny(23, 0)));
		assertTrue(hours.isActive(ny(3, 59)));
		assertFalse(hours.isActive(ny(4, 0)));
		assertFalse(hours.isActive(ny(21, 59)));
	}

	@Test
	void usesTheConfiguredZoneNotUtc() {
		ActiveHours hours = ActiveHours.parse("18:00", "00:00", "America/New_York");
		// 23:00 UTC on Oct 6 is 19:00 in New York (EDT).
		assertTrue(hours.isActive(Instant.parse("2026-10-06T23:00:00Z")));
		// 05:00 UTC is 01:00 in New York.
		assertFalse(hours.isActive(Instant.parse("2026-10-07T05:00:00Z")));
	}

	@Test
	void blankOrEqualTimesMeanAlwaysActive() {
		assertFalse(ActiveHours.parse("", "", "").isRestricted());
		assertFalse(ActiveHours.parse("18:00", "18:00", "America/New_York").isRestricted());
		assertTrue(ActiveHours.parse(null, null, null).isActive(ny(3, 0)));
	}

	@Test
	void rejectsUnparseableValues() {
		assertThrows(IllegalArgumentException.class, () -> ActiveHours.parse("6pm", "00:00", "America/New_York"));
		assertThrows(IllegalArgumentException.class, () -> ActiveHours.parse("18:00", "00:00", "Mars/Olympus"));
	}
}
