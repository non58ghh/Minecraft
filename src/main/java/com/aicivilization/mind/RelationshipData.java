package com.aicivilization.mind;

/**
 * How one agent regards a specific other agent. Mutable, updated only
 * through interactions that actually happened (a conversation, a shared
 * experience) — never set wholesale from outside.
 */
public final class RelationshipData {

	private double affinity; // -1 (hostile) .. 1 (close)
	private double trust; // 0 (none) .. 1 (full)
	private long lastInteractionTick;
	private int thingsLearnedFromThem;

	public RelationshipData() {
		this.affinity = 0.0;
		this.trust = 0.1;
		this.lastInteractionTick = -1;
		this.thingsLearnedFromThem = 0;
	}

	public RelationshipData(double affinity, double trust, long lastInteractionTick, int thingsLearnedFromThem) {
		this.affinity = clampAffinity(affinity);
		this.trust = clamp01(trust);
		this.lastInteractionTick = lastInteractionTick;
		this.thingsLearnedFromThem = thingsLearnedFromThem;
	}

	private static double clampAffinity(double v) {
		return Math.max(-1.0, Math.min(1.0, v));
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	public double affinity() {
		return affinity;
	}

	public double trust() {
		return trust;
	}

	public long lastInteractionTick() {
		return lastInteractionTick;
	}

	public int thingsLearnedFromThem() {
		return thingsLearnedFromThem;
	}

	public void recordConversation(long tick, double affinityDelta, double trustDelta) {
		this.affinity = clampAffinity(affinity + affinityDelta);
		this.trust = clamp01(trust + trustDelta);
		this.lastInteractionTick = tick;
	}

	public void recordToldSomething(long tick) {
		this.thingsLearnedFromThem++;
		recordConversation(tick, 0.02, 0.03);
	}
}
