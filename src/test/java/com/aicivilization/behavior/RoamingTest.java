package com.aicivilization.behavior;

import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamingTest {

	private static final Vec3 GROUND = new Vec3(0, 64, 0);

	@Test
	void walksFromNearHomeStayWithinReach() {
		Random random = new Random(1);
		Vec3 here = new Vec3(40, 64, 10);
		for (int i = 0; i < 200; i++) {
			Vec3 to = Roaming.destination(here, GROUND, 80, random::nextDouble);
			assertTrue(Roaming.flat(to, GROUND) <= Roaming.reach(80) + 1e-9);
		}
	}

	@Test
	void anAgentFarAwayHeadsBackTowardHomeGround() {
		Random random = new Random(2);
		Vec3 far = new Vec3(396, 69, -304);
		for (int i = 0; i < 50; i++) {
			Vec3 to = Roaming.destination(far, GROUND, 80, random::nextDouble);
			assertTrue(Roaming.flat(to, GROUND) <= Roaming.reach(80) * 0.5 + 1e-9);
			assertTrue(Roaming.flat(to, GROUND) < Roaming.flat(far, GROUND));
		}
	}

	@Test
	void longerSearchesMayRangeFurther() {
		assertTrue(Roaming.reach(160) > Roaming.reach(80));
		assertTrue(Roaming.reach(24) == Roaming.HOME_GROUND);
	}
}
