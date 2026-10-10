package com.aicivilization.behavior;

import java.util.function.DoubleSupplier;
import net.minecraft.world.phys.Vec3;

/**
 * Where a long walk (exploring, searching for trees or food) heads. People
 * range widely but come back: a walk goes out from where the agent is, but
 * stays within reach of its home ground (its home, or else where the
 * settlement was founded), and an agent that has strayed too far heads
 * back toward it first. Without this, walks in random directions carried
 * agents hundreds of blocks apart, where none could find another.
 */
final class Roaming {

	/** How far from its home ground an agent's walks reach, at least. */
	static final double HOME_GROUND = 64;
	private static final int TRIES = 8;

	private Roaming() {
	}

	/** How far a walk of this length may take it from its home ground: further for longer searches. */
	static double reach(double radius) {
		return Math.max(HOME_GROUND, radius * 0.75);
	}

	/**
	 * Where to head on a walk of about {@code radius} blocks from {@code here}
	 * (heights ignored). {@code random} gives uniform draws in [0, 1).
	 */
	static Vec3 destination(Vec3 here, Vec3 ground, double radius, DoubleSupplier random) {
		double reach = reach(radius);
		if (flat(here, ground) > reach) {
			// Strayed too far: back toward home ground, to somewhere in its nearer half.
			return around(ground, reach * 0.5, random);
		}
		for (int i = 0; i < TRIES; i++) {
			double angle = random.getAsDouble() * Math.PI * 2;
			double distance = radius * 0.5 + random.getAsDouble() * radius * 0.5;
			Vec3 there = here.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
			if (flat(there, ground) <= reach) {
				return there;
			}
		}
		// Every way out leads too far: wander across home ground instead.
		return around(ground, reach * 0.75, random);
	}

	private static Vec3 around(Vec3 centre, double maxDistance, DoubleSupplier random) {
		double angle = random.getAsDouble() * Math.PI * 2;
		double distance = random.getAsDouble() * maxDistance;
		return new Vec3(centre.x + Math.cos(angle) * distance, centre.y, centre.z + Math.sin(angle) * distance);
	}

	static double flat(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
