package com.aicivilization.reasoning;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReasoningGateTest {

	private static final UUID AGENT = UUID.randomUUID();
	private static final double[] CALM = {0.8, 0.8, 0.6, 0.6};

	private static ReasoningGate gate() {
		return new ReasoningGate(6000, 1200, 0.1, 0);
	}

	@Test
	void thinksOnceAtStartThenWaitsForTheInterval() {
		ReasoningGate gate = gate();
		assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(AGENT, 1000, null, 0, CALM));
		assertNull(gate.check(AGENT, 1001, null, 1, CALM));
		assertNull(gate.check(AGENT, 6999, null, 1, CALM));
		assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(AGENT, 7000, null, 1, CALM));
	}

	@Test
	void skipsRoutineReflectionWhenNothingChanged() {
		ReasoningGate gate = gate();
		gate.check(AGENT, 0, null, 5, CALM);
		assertNull(gate.check(AGENT, 6000, null, 5, CALM), "same memories and needs: same answer, so no call");
		assertNull(gate.check(AGENT, 12000, null, 5, new double[] {0.75, 0.8, 0.6, 0.6}), "small drift is not news");
		assertEquals(ReasoningGate.Trigger.ROUTINE,
				gate.check(AGENT, 12001, null, 5, new double[] {0.7, 0.8, 0.6, 0.6}), "a need moved by 0.1");
	}

	@Test
	void newMemoryCountsAsSomethingChanged() {
		ReasoningGate gate = gate();
		gate.check(AGENT, 0, null, 5, CALM);
		assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(AGENT, 6000, null, 6, CALM));
	}

	@Test
	void anOngoingCrisisTriggersOnlyOnce() {
		ReasoningGate gate = gate();
		gate.check(AGENT, 0, null, 0, CALM);
		assertEquals(ReasoningGate.Trigger.CRISIS, gate.check(AGENT, 2000, "social", 0, CALM));
		for (long t = 2001; t < 7999; t += 200) {
			assertNull(gate.check(AGENT, t, "social", 0, CALM), "same crisis must not re-trigger (t=" + t + ")");
		}
	}

	@Test
	void aDifferentCriticalNeedTriggersAfterTheCooldown() {
		ReasoningGate gate = gate();
		gate.check(AGENT, 0, null, 0, CALM);
		gate.check(AGENT, 2000, "social", 0, CALM);
		assertNull(gate.check(AGENT, 2500, "food", 0, CALM), "within the crisis cooldown");
		assertEquals(ReasoningGate.Trigger.CRISIS, gate.check(AGENT, 3200, "food", 0, CALM));
	}

	@Test
	void reEnteringACrisisTriggersAgain() {
		ReasoningGate gate = gate();
		gate.check(AGENT, 0, null, 0, CALM);
		gate.check(AGENT, 2000, "food", 0, CALM);
		assertNull(gate.check(AGENT, 3000, null, 0, CALM), "recovered");
		assertEquals(ReasoningGate.Trigger.CRISIS, gate.check(AGENT, 3500, "food", 0, CALM));
	}

	@Test
	void dailyCapStopsCallsUntilTheWindowRollsOver() {
		ReasoningGate gate = new ReasoningGate(100, 1200, 0.1, 3);
		long t = 0;
		for (int i = 0; i < 3; i++, t += 100) {
			assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(AGENT, t, null, i, CALM));
		}
		assertNull(gate.check(AGENT, t, null, 99, CALM), "cap reached");
		assertNull(gate.check(AGENT, t + 1, "food", 99, CALM), "cap applies to crises too");
		assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(AGENT, ReasoningGate.DAY_TICKS, null, 100, CALM));
	}

	@Test
	void agentsArePacedIndependently() {
		ReasoningGate gate = gate();
		UUID other = UUID.randomUUID();
		gate.check(AGENT, 0, null, 0, CALM);
		assertEquals(ReasoningGate.Trigger.ROUTINE, gate.check(other, 1, null, 0, CALM));
	}
}
