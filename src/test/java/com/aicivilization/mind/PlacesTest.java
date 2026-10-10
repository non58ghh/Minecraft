package com.aicivilization.mind;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacesTest {

	private static final long DAY = 24000;

	@Test
	void theSamePlaceSeenAgainIsRefreshedNotDoubled() {
		Places places = new Places();
		places.note(Places.Kind.WOODS, 100, 64, 100, 10);
		places.note(Places.Kind.WOODS, 105, 64, 102, 20);
		assertEquals(1, places.of(Places.Kind.WOODS, 20).size());
		assertEquals(20, places.of(Places.Kind.WOODS, 20).get(0).tick());
		places.note(Places.Kind.WOODS, 200, 64, 100, 30);
		assertEquals(2, places.of(Places.Kind.WOODS, 30).size());
	}

	@Test
	void animalsFadeInADayButAFieldIsKeptForDays() {
		Places places = new Places();
		places.note(Places.Kind.ANIMALS, 0, 64, 0, 0);
		places.note(Places.Kind.FIELD, 10, 64, 10, 0, null, true);
		assertFalse(places.knows(Places.Kind.ANIMALS, 2 * DAY));
		assertTrue(places.knows(Places.Kind.FIELD, 5 * DAY));
		assertFalse(places.knows(Places.Kind.FIELD, 11 * DAY));
	}

	@Test
	void theOldestGoesPastTheCap() {
		Places places = new Places();
		for (int i = 0; i < 20; i++) {
			places.note(Places.Kind.FIELD, i * 100, 64, 0, i);
		}
		assertEquals(8, places.of(Places.Kind.FIELD, 20).size());
		assertTrue(places.of(Places.Kind.FIELD, 20).stream().noneMatch(p -> p.x() == 0));
	}

	@Test
	void homesAreKnownByWhoseTheyAre() {
		Places places = new Places();
		UUID orrin = UUID.randomUUID();
		places.note(Places.Kind.HOME, 0, 64, 0, 0, orrin, false);
		places.note(Places.Kind.HOME, 50, 64, 50, 10, orrin, false);
		assertEquals(1, places.of(Places.Kind.HOME, 10).size());
		assertEquals(50, places.of(Places.Kind.HOME, 10).get(0).x());
	}

	@Test
	void aClearedStandIsForgottenAndNearestPicksTheClosest() {
		Places places = new Places();
		places.note(Places.Kind.WOODS, 0, 64, 0, 0);
		places.note(Places.Kind.WOODS, 300, 64, 0, 0);
		assertEquals(300, places.nearest(Places.Kind.WOODS, 250, 64, 0, 1).orElseThrow().x());
		places.forget(Places.Kind.WOODS, 290, 64, 0, 16);
		assertEquals(0, places.nearest(Places.Kind.WOODS, 250, 64, 0, 1).orElseThrow().x());
	}
}
