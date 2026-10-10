package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Homelessness pulls toward wood; a monster's blow pulls toward getting away. */
class DangerAndShelterDecisionTest {

	private static final Set<IntentType> DAY = EnumSet.of(IntentType.GATHER_MATERIALS, IntentType.SOCIALIZE,
			IntentType.EXPLORE, IntentType.REST, IntentType.IDLE);

	private static AgentMind agent(Needs needs) {
		return new AgentMind(new Identity(UUID.randomUUID(), "Thessaly", 0), new Personality(0.5, 0.5, 0.5, 0.5), needs);
	}

	@Test
	void aContentAgentWithNoHomeGoesForWood() {
		AgentMind thessaly = agent(new Needs(0.9, 1.0, 0.6, 0.5));
		var trace = thessaly.decide(100, DAY);
		assertEquals(IntentType.GATHER_MATERIALS, trace.chosen());
		assertTrue(trace.candidates().get(0).factors().containsKey("no home yet"));
	}

	@Test
	void withAHomeTheSameAgentHasNoSuchPull() {
		AgentMind thessaly = agent(new Needs(0.9, 1.0, 0.6, 0.5));
		thessaly.setHome(new Home(0, 64, 0, Design.hut(), 10));
		var trace = thessaly.decide(100, DAY);
		assertNotEquals(IntentType.GATHER_MATERIALS, trace.chosen());
	}

	@Test
	void beingAttackedOutweighsLoneliness() {
		AgentMind wilder = agent(new Needs(0.9, 0.8, 0.0, 1.0));
		Set<IntentType> choices = EnumSet.of(IntentType.SOCIALIZE, IntentType.SEEK_SAFETY, IntentType.IDLE);
		assertEquals(IntentType.SOCIALIZE, wilder.decide(100, choices).chosen());
		wilder.noteUnderAttack(true);
		assertEquals(IntentType.SEEK_SAFETY, wilder.decide(101, choices).chosen());
	}
}
