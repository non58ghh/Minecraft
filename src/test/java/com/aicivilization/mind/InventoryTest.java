package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

	private static AgentMind newMind(double safety, double ambition) {
		return new AgentMind(
				new Identity(UUID.randomUUID(), "Wren", 0),
				new Personality(0.5, 0.5, 0.5, ambition),
				new Needs(0.8, safety, 0.8, 0.8));
	}

	@Test
	void receivingTheSameItemMergesIntoOneStack() {
		AgentMind mind = newMind(0.8, 0.5);

		mind.receiveItem(10, "minecraft:oak_log", 3);
		mind.receiveItem(50, "minecraft:oak_log", 2);
		mind.receiveItem(60, "minecraft:beef", 1);

		assertEquals(2, mind.possessions().size());
		assertEquals(5, mind.countOf("minecraft:oak_log"));
		assertEquals(6, mind.totalItems());
		assertEquals(10, mind.possessions().get(0).acquiredTick(), "a merged stack keeps its first acquisition tick");
	}

	@Test
	void takingMoreThanTheAgentHasChangesNothing() {
		AgentMind mind = newMind(0.8, 0.5);
		mind.receiveItem(0, "minecraft:oak_log", 2);

		assertFalse(mind.takeItem("minecraft:oak_log", 3));
		assertFalse(mind.takeItem("minecraft:stone", 1));

		assertEquals(2, mind.countOf("minecraft:oak_log"));
	}

	@Test
	void takingTheLastOfAStackRemovesIt() {
		AgentMind mind = newMind(0.8, 0.5);
		mind.receiveItem(0, "minecraft:beef", 1);

		assertTrue(mind.takeItem("minecraft:beef", 1));

		assertEquals(0, mind.countOf("minecraft:beef"));
		assertTrue(mind.possessions().isEmpty());
	}

	@Test
	void nonPositiveQuantitiesAreIgnored() {
		AgentMind mind = newMind(0.8, 0.5);

		mind.receiveItem(0, "minecraft:oak_log", 0);
		mind.receiveItem(0, "minecraft:oak_log", -4);

		assertTrue(mind.possessions().isEmpty());
	}

	@Test
	void anExposedAgentWithMaterialsPrefersBuildingToResting() {
		AgentMind mind = newMind(0.2, 0.5);

		DecisionTrace trace = mind.decide(100, EnumSet.of(IntentType.BUILD_SHELTER, IntentType.REST, IntentType.IDLE));

		assertEquals(IntentType.BUILD_SHELTER, trace.chosen());
		assertTrue(trace.candidates().stream()
				.filter(c -> c.intent() == IntentType.BUILD_SHELTER)
				.anyMatch(c -> c.factors().containsKey("exposed")), "the Why panel should show what drove it");
	}

	@Test
	void aFullPackMakesGatheringLessAttractive() {
		AgentMind empty = newMind(0.8, 0.5);
		AgentMind full = newMind(0.8, 0.5);
		full.receiveItem(0, "minecraft:oak_log", 30);

		double emptyScore = scoreOf(empty, IntentType.GATHER_MATERIALS);
		double fullScore = scoreOf(full, IntentType.GATHER_MATERIALS);

		assertTrue(fullScore < emptyScore - 0.2, "carrying 30 items should cost about 0.3 of score");
	}

	@Test
	void ambitiousAgentsGatherMoreEagerly() {
		assertTrue(scoreOf(newMind(0.8, 0.9), IntentType.GATHER_MATERIALS)
				> scoreOf(newMind(0.8, 0.1), IntentType.GATHER_MATERIALS));
	}

	private static double scoreOf(AgentMind mind, IntentType intent) {
		return mind.decide(1, EnumSet.of(intent, IntentType.IDLE)).candidates().stream()
				.filter(c -> c.intent() == intent)
				.findFirst().orElseThrow().score();
	}
}
