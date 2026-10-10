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
		AgentMind wilder = agent(new Needs(0.9, 0.55, 0.0, 1.0));
		Set<IntentType> choices = EnumSet.of(IntentType.SOCIALIZE, IntentType.SEEK_SAFETY, IntentType.IDLE);
		assertEquals(IntentType.SOCIALIZE, wilder.decide(100, choices).chosen());
		wilder.noteThreat(true);
		wilder.noteUnderAttack(true);
		assertEquals(IntentType.SEEK_SAFETY, wilder.decide(101, choices).chosen());
	}

	private static AgentMind fighter(double risk) {
		AgentMind m = new AgentMind(new Identity(UUID.randomUUID(), "Bram", 0), new Personality(0.5, risk, 0.5, 0.5),
				new Needs(0.9, 0.55, 0.8, 1.0));
		m.noteThreat(true);
		m.noteUnderAttack(true);
		return m;
	}

	private static final Set<IntentType> ATTACKED = EnumSet.of(IntentType.SEEK_SAFETY, IntentType.FIGHT, IntentType.IDLE);

	@Test
	void theBoldFightBackAndTheCautiousRun() {
		AgentMind bold = fighter(0.8);
		bold.noteCombat(false, 1.0, false);
		assertEquals(IntentType.FIGHT, bold.decide(100, ATTACKED).chosen());
		AgentMind cautious = fighter(0.2);
		cautious.noteCombat(false, 1.0, false);
		assertEquals(IntentType.SEEK_SAFETY, cautious.decide(100, ATTACKED).chosen());
	}

	@Test
	void aSwordStiffensAnAverageNerve() {
		AgentMind unarmed = fighter(0.5);
		unarmed.noteCombat(false, 1.0, false);
		AgentMind armed = fighter(0.5);
		armed.noteCombat(true, 1.0, false);
		assertEquals(IntentType.FIGHT, armed.decide(100, ATTACKED).chosen());
		double gap = score(armed, IntentType.FIGHT) - score(unarmed, IntentType.FIGHT);
		assertEquals(0.25, gap, 0.06);
	}

	@Test
	void badlyHurtEvenTheBoldRun() {
		AgentMind bold = fighter(0.8);
		bold.noteCombat(true, 0.15, false);
		assertEquals(IntentType.SEEK_SAFETY, bold.decide(100, ATTACKED).chosen());
	}

	@Test
	void aMonsterGoingForSomeoneElseDrawsTheSociableIn() {
		AgentMind m = new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0), new Personality(0.5, 0.6, 0.9, 0.5),
				new Needs(0.9, 0.8, 0.8, 1.0));
		m.noteThreat(true);
		m.noteCombat(true, 1.0, true);
		assertEquals(IntentType.FIGHT, m.decide(100, ATTACKED).chosen());
	}

	private static double score(AgentMind m, IntentType type) {
		return m.decide(100, ATTACKED).candidates().stream().filter(c -> c.intent() == type).findFirst().orElseThrow().score();
	}
}
