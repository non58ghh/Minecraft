package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The loop seen live: a starving agent kept socializing because of a pile of stale goals. */
class GoalsAndBeliefsTest {

	private static AgentMind sociableMind(double food, double safety) {
		return new AgentMind(
				new Identity(UUID.randomUUID(), "Petra", 0),
				new Personality(0.55, 0.31, 0.88, 0.09),
				new Needs(food, safety, 1.0, 0.18));
	}

	private static long activeGoals(AgentMind mind) {
		return mind.goals().stream().filter(Goal::active).count();
	}

	@Test
	void aNewGoalReplacesTheOldOneForTheSameIntent() {
		AgentMind mind = sociableMind(0.8, 0.8);
		mind.addGoal(100, "Find Idris and start a conversation", 0.92, IntentType.SOCIALIZE);
		mind.addGoal(200, "Find Idris and have a genuine conversation", 0.95, IntentType.SOCIALIZE);

		assertEquals(1, activeGoals(mind));
		assertEquals("Find Idris and have a genuine conversation",
				mind.goals().stream().filter(Goal::active).findFirst().orElseThrow().description());
	}

	@Test
	void atMostThreeGoalsStayActive() {
		AgentMind mind = sociableMind(0.8, 0.8);
		mind.addGoal(1, "a", 0.5, IntentType.SOCIALIZE);
		mind.addGoal(2, "b", 0.5, IntentType.EXPLORE);
		mind.addGoal(3, "c", 0.5, IntentType.GATHER_MATERIALS);
		mind.addGoal(4, "d", 0.5, IntentType.BUILD_SHELTER);

		assertEquals(AgentMind.MAX_ACTIVE_GOALS, activeGoals(mind));
	}

	@Test
	void goalsFadeAfterAboutADay() {
		AgentMind mind = sociableMind(0.8, 0.8);
		mind.addGoal(0, "Find Idris", 0.95, IntentType.SOCIALIZE);
		mind.expireGoals(AgentMind.GOAL_LIFETIME_TICKS + 1);

		assertEquals(0, activeGoals(mind));
	}

	@Test
	void aStarvingAgentForagesEvenWithAStrongSocialGoal() {
		AgentMind mind = sociableMind(0.0, 0.0);
		mind.addGoal(100, "Find Idris and have a genuine conversation", 0.95, IntentType.SOCIALIZE);

		DecisionTrace trace = mind.decide(200, EnumSet.of(IntentType.FORAGE_FOOD, IntentType.SOCIALIZE,
				IntentType.GATHER_MATERIALS, IntentType.EXPLORE, IntentType.REST, IntentType.IDLE));

		assertEquals(IntentType.FORAGE_FOOD, trace.chosen());
	}

	@Test
	void aWellFedAgentStillFollowsItsGoal() {
		AgentMind mind = sociableMind(0.9, 0.9);
		mind.addGoal(100, "Find Idris and have a genuine conversation", 0.95, IntentType.SOCIALIZE);

		DecisionTrace trace = mind.decide(200, EnumSet.of(IntentType.FORAGE_FOOD, IntentType.SOCIALIZE,
				IntentType.EXPLORE, IntentType.REST, IntentType.IDLE));

		assertEquals(IntentType.SOCIALIZE, trace.chosen());
	}

	@Test
	void aNearRepeatOfARecentBeliefIsSkipped() {
		AgentMind mind = sociableMind(0.8, 0.8);
		assertNotNull(mind.formBelief(1, "Idris is someone worth knowing; social connection fulfills my needs",
				0.85, new Provenance.Perceived()));
		assertNull(mind.formBelief(2, "Idris is someone worth knowing; connection fulfills my social needs",
				0.85, new Provenance.Perceived()));
		assertNotNull(mind.formBelief(3, "The forest to the east has more cows than the plains",
				0.7, new Provenance.Perceived()));
		assertEquals(2, mind.beliefs().size());
	}
}
