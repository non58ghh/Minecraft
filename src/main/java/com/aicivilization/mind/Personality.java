package com.aicivilization.mind;

import java.util.random.RandomGenerator;

/**
 * Fixed personality traits, each in {@code [0, 1]}. Rolled once at agent
 * creation and never mutated — personality is not a lever the simulation
 * pulls to steer behavior; it's an input the agent's own decisions read.
 */
public record Personality(double curiosity, double risk, double sociability, double ambition) {

	public Personality {
		curiosity = clamp(curiosity);
		risk = clamp(risk);
		sociability = clamp(sociability);
		ambition = clamp(ambition);
	}

	private static double clamp(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	/** Rolls a random personality with independent uniform traits. */
	public static Personality random(RandomGenerator rng) {
		return new Personality(rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), rng.nextDouble());
	}
}
