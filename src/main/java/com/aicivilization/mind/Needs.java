package com.aicivilization.mind;

/**
 * Mutable survival/social needs, each in {@code [0, 1]} where {@code 1.0}
 * means fully satisfied and {@code 0.0} means critical. Needs decay over
 * time and are pushed back up by the agent's own actions (eating, being
 * defended, socializing, belonging to a place/group) — never set directly
 * by anything outside the agent's own experience.
 */
public final class Needs {

	/** Below this, a need is considered a crisis (drives reasoning triggers). */
	public static final double CRISIS_THRESHOLD = 0.15;

	private double food;
	private double safety;
	private double social;
	private double belonging;

	public Needs(double food, double safety, double social, double belonging) {
		this.food = clamp(food);
		this.safety = clamp(safety);
		this.social = clamp(social);
		this.belonging = clamp(belonging);
	}

	/** A newly created agent starts reasonably, but not perfectly, provisioned. */
	public static Needs initial() {
		return new Needs(0.8, 0.8, 0.6, 0.6);
	}

	private static double clamp(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	public double food() {
		return food;
	}

	public double safety() {
		return safety;
	}

	public double social() {
		return social;
	}

	public double belonging() {
		return belonging;
	}

	public void adjustFood(double delta) {
		food = clamp(food + delta);
	}

	public void adjustSafety(double delta) {
		safety = clamp(safety + delta);
	}

	public void adjustSocial(double delta) {
		social = clamp(social + delta);
	}

	public void adjustBelonging(double delta) {
		belonging = clamp(belonging + delta);
	}

	/**
	 * Applies passive decay proportional to elapsed ticks. Rates are per-need
	 * so, e.g., hunger creeps down faster than belonging.
	 */
	public void decay(long elapsedTicks) {
		if (elapsedTicks <= 0) {
			return;
		}
		double t = elapsedTicks;
		adjustFood(-0.00006 * t);
		adjustSafety(-0.00002 * t);
		adjustSocial(-0.00003 * t);
		adjustBelonging(-0.00001 * t);
	}

	public boolean hasCrisis() {
		return food < CRISIS_THRESHOLD || safety < CRISIS_THRESHOLD
				|| social < CRISIS_THRESHOLD || belonging < CRISIS_THRESHOLD;
	}

	public double lowestValue() {
		return Math.min(Math.min(food, safety), Math.min(social, belonging));
	}

	public String lowestName() {
		double lowest = lowestValue();
		if (lowest == food) {
			return "food";
		}
		if (lowest == safety) {
			return "safety";
		}
		if (lowest == social) {
			return "social";
		}
		return "belonging";
	}
}
