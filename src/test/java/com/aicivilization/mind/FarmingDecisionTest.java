package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FarmingDecisionTest {

	private static final Set<IntentType> FOOD_CHOICES = EnumSet.of(
			IntentType.FORAGE_FOOD, IntentType.FARM, IntentType.SOCIALIZE, IntentType.EXPLORE, IntentType.REST, IntentType.IDLE);

	private static AgentMind mind(double food, double ambition) {
		return new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0),
				new Personality(0.5, 0.5, 0.5, ambition), new Needs(food, 0.8, 0.8, 0.8));
	}

	@Test
	void aHungryAgentForagesFirst() {
		assertEquals(IntentType.FORAGE_FOOD, mind(0.0, 0.5).decide(100, FOOD_CHOICES).chosen());
	}

	@Test
	void afterRepeatedFailedSearchesItTriesGrowingFood() {
		AgentMind iris = mind(0.0, 0.5);
		for (int i = 0; i < 4; i++) {
			iris.noteFoodSearch(false);
		}
		assertEquals(IntentType.FARM, iris.decide(100, FOOD_CHOICES).chosen());
	}

	@Test
	void findingFoodResetsTheScarcity() {
		AgentMind iris = mind(0.0, 0.5);
		for (int i = 0; i < 4; i++) {
			iris.noteFoodSearch(false);
		}
		iris.noteFoodSearch(true);
		assertEquals(0, iris.failedFoodSearches());
		assertEquals(IntentType.FORAGE_FOOD, iris.decide(100, FOOD_CHOICES).chosen());
	}

	@Test
	void aWellFedAgentDoesNotFarmCompulsively() {
		AgentMind iris = mind(0.95, 0.2);
		assertNotEquals(IntentType.FARM, iris.decide(100, FOOD_CHOICES).chosen());
	}

	@Test
	void aFarmingGoalStillCountsWhileStarving() {
		AgentMind iris = mind(0.0, 0.5);
		iris.addGoal(50, "Plant a wheat field near my shelter", 0.9, IntentType.FARM);
		assertEquals(IntentType.FARM, iris.decide(100, FOOD_CHOICES).chosen());
	}

	@Test
	void hungerOutranksAVagueSenseOfDangerButNotAMonsterInSight() {
		AgentMind iris = new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0),
				new Personality(0.5, 0.5, 0.5, 0.5), new Needs(0.0, 0.0, 0.8, 0.8));
		Set<IntentType> choices = EnumSet.of(IntentType.FORAGE_FOOD, IntentType.SEEK_SAFETY, IntentType.REST);
		iris.noteThreat(false);
		assertEquals(IntentType.FORAGE_FOOD, iris.decide(100, choices).chosen());
		iris.noteThreat(true);
		assertEquals(IntentType.SEEK_SAFETY, iris.decide(101, choices).chosen());
	}

	@Test
	void aWellStockedAgentStopsFarmingCompulsively() {
		AgentMind orrin = mind(0.8, 0.9);
		for (int i = 0; i < 4; i++) {
			orrin.noteFoodSearch(false);
		}
		Set<IntentType> choices = EnumSet.of(IntentType.FARM, IntentType.REST, IntentType.EXPLORE);
		assertEquals(IntentType.FARM, orrin.decide(100, choices).chosen());
		orrin.noteStock(200, 0);
		assertNotEquals(IntentType.FARM, orrin.decide(101, choices).chosen());
	}
}
