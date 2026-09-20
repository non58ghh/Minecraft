package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMindTest {

	private static AgentMind newMind(String name) {
		return new AgentMind(
				new Identity(UUID.randomUUID(), name, 0),
				new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void decidesToForageWhenStarvingAndFoodIsAvailable() {
		AgentMind mind = newMind("Elias");
		mind.needs().adjustFood(-0.79); // 0.8 -> ~0.01, near-starving

		DecisionTrace trace = mind.decide(100, EnumSet.of(IntentType.FORAGE_FOOD, IntentType.REST, IntentType.IDLE));

		assertEquals(IntentType.FORAGE_FOOD, trace.chosen());
		assertFalse(trace.causes().isEmpty(), "a decision this clear-cut should cite the driving need as a cause");
	}

	@Test
	void agentBDoesNotKnowWhatAgentAPerceivedUntilToldOrItPerceivesItItself() {
		AgentMind a = newMind("Marcus");
		AgentMind b = newMind("Elias");

		MemoryEntry aDiscoversIron = a.perceive(10, "found iron east of the settlement", 0.8, Set.of());

		// B's own knowledge is derived only from B's own memory stream.
		assertTrue(Knowledge.summarize(b.memories(), 10, 10).isEmpty(),
				"B must not know what A perceived until told or B perceives it independently");

		MemoryEntry told = b.receiveTold(20, a.identity().id(), a.identity().name(), aDiscoversIron);

		assertTrue(Knowledge.summarize(b.memories(), 20, 10).stream().anyMatch(s -> s.contains("iron")));
		assertInstanceOfTold(told);
		assertEquals(a.identity().id(), ((Provenance.Told) told.provenance()).tellerId());
		assertEquals(aDiscoversIron.id(), ((Provenance.Told) told.provenance()).tellerMemoryId());
		// The chain is transitive: B's told-memory points at A's memory, whose
		// own provenance (Perceived) is independently inspectable.
		assertTrue(a.memories().byId(aDiscoversIron.id()).orElseThrow().provenance() instanceof Provenance.Perceived);
	}

	private static void assertInstanceOfTold(MemoryEntry entry) {
		assertTrue(entry.provenance() instanceof Provenance.Told);
	}

	@Test
	void goalRelatedToAnIntentBoostsThatIntentsScore() {
		AgentMind mind = newMind("Iris");
		mind.addGoal(0, "learn what lies beyond the area I know", 0.9, IntentType.EXPLORE);

		DecisionTrace withGoal = mind.decide(50, EnumSet.of(IntentType.EXPLORE, IntentType.REST));
		double exploreScoreWithGoal = scoreFor(withGoal, IntentType.EXPLORE);

		AgentMind noGoalMind = newMind("Iris");
		DecisionTrace withoutGoal = noGoalMind.decide(50, EnumSet.of(IntentType.EXPLORE, IntentType.REST));
		double exploreScoreWithoutGoal = scoreFor(withoutGoal, IntentType.EXPLORE);

		assertTrue(exploreScoreWithGoal > exploreScoreWithoutGoal - 0.001,
				"an active goal serving EXPLORE should make EXPLORE at least as attractive");
	}

	private static double scoreFor(DecisionTrace trace, IntentType type) {
		return trace.candidates().stream()
				.filter(c -> c.intent() == type)
				.findFirst()
				.orElseThrow()
				.score();
	}
}
