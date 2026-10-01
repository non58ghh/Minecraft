package com.aicivilization.behavior;

import com.aicivilization.mind.IntentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPacingTest {

	@Test
	void decidesImmediatelyThenOnlyEveryInterval() {
		DecisionPacing pacing = new DecisionPacing(60, 20);
		assertTrue(pacing.shouldDecide(1000));
		pacing.onDecided(1000);

		for (long t = 1001; t < 1060; t++) {
			assertFalse(pacing.shouldDecide(t), "resting/idle agents must not re-decide every tick (t=" + t + ")");
		}
		assertTrue(pacing.shouldDecide(1060));
	}

	@Test
	void finishedTaskAllowsAnEarlierDecisionButNotWithinTheMinimumGap() {
		DecisionPacing pacing = new DecisionPacing(60, 20);
		pacing.onDecided(1000);
		pacing.onTaskFinished(); // e.g. arrived next to a social target on the same tick

		assertFalse(pacing.shouldDecide(1001));
		assertFalse(pacing.shouldDecide(1019));
		assertTrue(pacing.shouldDecide(1020));

		pacing.onDecided(1020);
		assertFalse(pacing.shouldDecide(1021), "deciding clears the finished-task flag");
	}

	@Test
	void logsOnlyWhenTheChosenIntentChanges() {
		DecisionPacing pacing = new DecisionPacing(60, 20);
		assertTrue(pacing.shouldLog(IntentType.REST));
		assertFalse(pacing.shouldLog(IntentType.REST));
		assertFalse(pacing.shouldLog(IntentType.REST));
		assertTrue(pacing.shouldLog(IntentType.SOCIALIZE));
		assertTrue(pacing.shouldLog(IntentType.REST));
	}
}
