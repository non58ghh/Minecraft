package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Starving people go to others when their own searches fail; the fed notice the starving. */
class HungerHelpDecisionTest {

	private static final Set<IntentType> CHOICES = EnumSet.of(IntentType.FORAGE_FOOD, IntentType.SOCIALIZE, IntentType.IDLE);

	private static AgentMind elias(double food) {
		return new AgentMind(new Identity(UUID.randomUUID(), "Elias", 0), new Personality(0.5, 0.5, 0.3, 0.5),
				new Needs(food, 0.8, 0.0, 0.5));
	}

	@Test
	void starvingItForagesFirst() {
		assertEquals(IntentType.FORAGE_FOOD, elias(0.0).decide(100, CHOICES).chosen());
	}

	@Test
	void whenItsSearchesKeepFailingItGoesToPeople() {
		AgentMind elias = elias(0.0);
		elias.noteFoodSearch(false);
		elias.noteFoodSearch(false);
		var trace = elias.decide(100, CHOICES);
		assertEquals(IntentType.SOCIALIZE, trace.chosen());
		assertEquals(0.5, trace.candidates().get(0).factors().get("hoping someone will share food"));
	}

	@Test
	void someoneStarvingInSightDrawsTheKindWithFoodToSpare() {
		AgentMind vesna = new AgentMind(new Identity(UUID.randomUUID(), "Vesna", 0), new Personality(0.5, 0.5, 0.8, 0.5),
				new Needs(0.9, 0.9, 0.9, 0.9));
		Set<IntentType> choices = EnumSet.of(IntentType.SOCIALIZE, IntentType.EXPLORE, IntentType.REST, IntentType.IDLE);
		assertNotEquals(IntentType.SOCIALIZE, vesna.decide(100, choices).chosen());
		vesna.noteSomeoneStarving(true);
		assertEquals(IntentType.SOCIALIZE, vesna.decide(101, choices).chosen());
	}
}
