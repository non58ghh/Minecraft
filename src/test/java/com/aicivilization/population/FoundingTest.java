package com.aicivilization.population;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoundingTest {

	@Test
	void emptyWorldGetsTheConfiguredFounders() {
		assertEquals(10, Founding.foundersToSpawn(10, 0, 64));
	}

	@Test
	void worldThatEverHadAgentsGetsNone() {
		assertEquals(0, Founding.foundersToSpawn(10, 1, 64), "a population that died out stays dead");
	}

	@Test
	void zeroOrNegativeTurnsItOff() {
		assertEquals(0, Founding.foundersToSpawn(0, 0, 64));
		assertEquals(0, Founding.foundersToSpawn(-3, 0, 64));
	}

	@Test
	void cappedByMaxAgentsUnlessUncapped() {
		assertEquals(64, Founding.foundersToSpawn(100, 0, 64));
		assertEquals(100, Founding.foundersToSpawn(100, 0, 0));
	}
}
