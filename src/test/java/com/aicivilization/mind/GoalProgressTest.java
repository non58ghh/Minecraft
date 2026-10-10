package com.aicivilization.mind;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalProgressTest {

	@Test
	void aGoalActedOnThreeTimesIsDoneAndStopsPulling() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 1.0, 0.8));
		mind.addGoal(10, "invite Hollis and Nadia to talk about farming together", 0.8, IntentType.SOCIALIZE);
		assertTrue(mind.noteGoalProgress(IntentType.SOCIALIZE, 20).isEmpty());
		assertTrue(mind.noteGoalProgress(IntentType.FARM, 25).isEmpty());
		assertTrue(mind.noteGoalProgress(IntentType.SOCIALIZE, 30).isEmpty());
		Goal done = mind.noteGoalProgress(IntentType.SOCIALIZE, 40).orElseThrow();
		assertEquals(3, done.progress());
		assertFalse(mind.goals().stream().anyMatch(Goal::active));
		assertTrue(mind.memories().retrieve(41, 3).stream()
				.anyMatch(m -> m.description().startsWith("I spent time on")));
	}
}
