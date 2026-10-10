package com.aicivilization.action;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sign says what it says; a reader takes it the way a person would. */
class SignWordsTest {

	@Test
	void directionsFollowTheCompass() {
		assertEquals("north", SignWords.direction(0, -40));
		assertEquals("south", SignWords.direction(0, 40));
		assertEquals("east", SignWords.direction(40, 0));
		assertEquals("west", SignWords.direction(-40, 0));
		assertEquals("north-east", SignWords.direction(30, -30));
		assertEquals("south-west", SignWords.direction(-30, 30));
	}

	@Test
	void aTreesSignFitsAndSaysWhereFromHere() {
		List<String> lines = SignWords.trees(0, -50, "Thessaly", 33);
		assertEquals(4, lines.size());
		lines.forEach(line -> assertTrue(line.length() <= SignWords.LINE_LENGTH, line));
		assertEquals("Trees / 48 blocks / north / - Thessaly d33", SignWords.join(lines));
	}

	@Test
	void aReaderFindsTheTreesItPointsTo() {
		int[] step = SignWords.treesAt(SignWords.join(SignWords.trees(40, 0, "Iris", 2))).orElseThrow();
		assertEquals(40, step[0]);
		assertEquals(0, step[1]);
	}

	@Test
	void aPlayersWordingWorksToo() {
		int[] north = SignWords.treesAt("forest 60 north").orElseThrow();
		assertEquals(0, north[0]);
		assertEquals(-60, north[1]);
		int[] diagonal = SignWords.treesAt("Wood to the south east").orElseThrow();
		assertTrue(diagonal[0] > 0 && diagonal[1] > 0, "south-east, about the default distance");
	}

	@Test
	void notEverySignAboutTreesPointsToThem() {
		assertTrue(SignWords.treesAt("No trees here").isEmpty());
		assertTrue(SignWords.treesAt("Trees!").isEmpty(), "no direction, nowhere to go");
		assertTrue(SignWords.treesAt("Iris's home, north side").isEmpty());
	}

	@Test
	void warningsAreRecognised() {
		assertTrue(SignWords.warns(SignWords.join(SignWords.danger("zombie", "Bram", 12))));
		assertTrue(SignWords.warns("Creepers about"));
		assertFalse(SignWords.warns("Trees / 48 blocks / north"));
	}
}
