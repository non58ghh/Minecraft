package com.aicivilization.reasoning;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallBudgetTest {

	@Test
	void startsHalfFullAndRefillsOverTheHour() {
		AtomicLong now = new AtomicLong(0);
		CallBudget b = new CallBudget(40, now::get);
		for (int i = 0; i < 20; i++) {
			assertTrue(b.take(false));
		}
		assertFalse(b.take(false), "half an hour's calls spent at once");
		now.addAndGet(90_000); // 1.5 minutes: one call's worth
		assertTrue(b.take(false));
		assertFalse(b.take(false));
	}

	@Test
	void extrasOnlyWhileHalfTheHourIsLeft() {
		AtomicLong now = new AtomicLong(0);
		CallBudget b = new CallBudget(40, now::get);
		assertTrue(b.hasRoom(true));
		assertTrue(b.take(true));
		assertFalse(b.hasRoom(true), "below half: write-ups wait");
		assertTrue(b.hasRoom(false), "an agent can still think");
		now.addAndGet(3_600_000);
		assertTrue(b.take(true));
	}

	@Test
	void noCeilingMeansNoCeiling() {
		CallBudget b = CallBudget.unlimited();
		for (int i = 0; i < 1000; i++) {
			assertTrue(b.take(true));
		}
	}
}
