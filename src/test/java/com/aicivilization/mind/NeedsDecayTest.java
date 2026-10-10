package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** How fast needs wear down on their own: loneliness and rootlessness take days, not hours. */
class NeedsDecayTest {

	private static final long DAY = 24000;

	@Test
	void aDayAloneLeavesSomeCompanyAndBelonging() {
		Needs needs = new Needs(1.0, 1.0, 1.0, 1.0);
		needs.decay(DAY);
		assertEquals(0.712, needs.social(), 0.001);
		assertEquals(0.88, needs.belonging(), 0.001);
		assertFalse(needs.hasCrisis(), "one quiet day is no crisis");
	}

	@Test
	void lonelinessBecomesACrisisOnlyAfterAboutThreeDaysAlone() {
		Needs needs = new Needs(1.0, 1.0, 1.0, 1.0);
		needs.decay(2 * DAY);
		assertFalse(needs.social() < Needs.CRISIS_THRESHOLD);
		needs.decay(DAY);
		assertEquals(0.136, needs.social(), 0.001);
	}
}
