package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BirthsAndArrivalsTest {

	private static AgentMind mind(String name) {
		return new AgentMind(new Identity(UUID.randomUUID(), name, 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	private static void fond(AgentMind a, AgentMind b) {
		a.relationships().with(b.identity().id()).recordConversation(0, 0.7, 0.5);
		b.relationships().with(a.identity().id()).recordConversation(0, 0.7, 0.5);
	}

	@Test
	void onlyAFondCoupleTogetherAtHomeFedAndSafe() {
		AgentMind a = mind("Iris"), b = mind("Bram");
		assertFalse(Births.couldHaveChild(a, b, 0, 3, 4, 5, false), "strangers");
		fond(a, b);
		assertTrue(Births.couldHaveChild(a, b, 0, 3, 4, 5, false));
		assertFalse(Births.couldHaveChild(a, b, 0, 30, 4, 5, false), "apart");
		assertFalse(Births.couldHaveChild(a, b, 0, 3, 40, 5, false), "away from home");
		assertFalse(Births.couldHaveChild(a, b, 0, 3, 4, 5, true), "a baby already");
		a.needs().adjustFood(-0.5);
		assertFalse(Births.couldHaveChild(a, b, 0, 3, 4, 5, false), "hungry");
	}

	@Test
	void notWithTheirOwnChildOrBetweenSiblings() {
		AgentMind a = mind("Iris"), b = mind("Bram");
		b.setParents(List.of(a.identity().id(), UUID.randomUUID()));
		fond(a, b);
		assertFalse(Births.couldHaveChild(a, b, 10 * AgentMind.GROWING_UP_TICKS, 3, 4, 5, false));
		AgentMind c = mind("Wren"), d = mind("Ash");
		List<UUID> parents = List.of(UUID.randomUUID(), UUID.randomUUID());
		c.setParents(parents);
		d.setParents(parents);
		fond(c, d);
		assertFalse(Births.couldHaveChild(c, d, 10 * AgentMind.GROWING_UP_TICKS, 3, 4, 5, false));
	}

	@Test
	void aChildTakesAfterBothParentsGiveOrTake() {
		Personality p = Births.blend(new Personality(0.2, 0.2, 0.2, 0.2), new Personality(0.8, 0.8, 0.8, 0.8), new Random(1));
		for (double v : new double[] {p.curiosity(), p.risk(), p.sociability(), p.ambition()}) {
			assertTrue(v >= 0.35 && v <= 0.65, "near the middle: " + v);
		}
	}

	@Test
	void arrivalsAreIrregular() {
		Random random = new Random(7);
		long min = Long.MAX_VALUE, max = 0;
		int alone = 0;
		for (int i = 0; i < 2000; i++) {
			long gap = Wanderers.nextGap(random);
			min = Math.min(min, gap);
			max = Math.max(max, gap);
			assertTrue(gap >= Wanderers.MIN_GAP && gap <= Wanderers.MAX_GAP);
			if (Wanderers.partySize(random) == 1) {
				alone++;
			}
		}
		assertTrue(min < Wanderers.DAY / 2 && max > 8 * Wanderers.DAY, "waits from hours to weeks: " + min + ".." + max);
		assertTrue(alone > 1200 && alone < 1600, "mostly alone: " + alone);
	}

	@Test
	void foundingStillOnlyForANewWorld() {
		assertEquals(0, Founding.foundersToSpawn(10, 3, 64));
	}
}
