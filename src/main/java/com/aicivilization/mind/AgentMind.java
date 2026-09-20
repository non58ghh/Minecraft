package com.aicivilization.mind;

import com.aicivilization.events.Cause;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The authoritative state and cognition of one agent. This is a plain Java
 * object with <b>no</b> dependency on Minecraft entity/world types — an
 * {@code AgentEntity} is a temporary physical embodiment that reads and
 * writes to an {@code AgentMind}, not the other way around. That
 * separation is what lets minds outlive entities, be simulated offline, and
 * eventually support death/rebirth and generations without a rewrite.
 *
 * <p><b>Epistemic boundary (enforced by construction, not convention):</b>
 * no method here accepts a Minecraft {@code Level}/{@code ServerLevel}/
 * {@code MinecraftServer}, an {@code EventLog}, a {@code PopulationRegistry},
 * or another {@code AgentMind}. The only inputs to cognition are this
 * mind's own state plus plain data ({@code String}, {@code double},
 * {@code UUID}, {@link MemoryEntry}) already extracted by a perception
 * system or a conversation exchange orchestrated by the embodiment layer.
 * This is what makes "no global/telepathic knowledge" a real guarantee
 * instead of a hope — see {@code AgentMindEpistemicBoundaryTest}.
 */
public final class AgentMind {

	private static final int MAX_RECENT_DECISIONS = 50;

	private final Identity identity;
	private final Personality personality;
	private final Needs needs;
	private final MemoryStream memories = new MemoryStream();
	private final Relationships relationships = new Relationships();
	private final List<Belief> beliefs = new ArrayList<>();
	private final List<Goal> goals = new ArrayList<>();
	private final List<Possession> possessions = new ArrayList<>();
	private final Deque<DecisionTrace> recentDecisions = new ArrayDeque<>();

	private long nextBeliefId = 1;
	private long nextGoalId = 1;
	private long nextDecisionId = 1;
	private boolean alive = true;

	public AgentMind(Identity identity, Personality personality, Needs needs) {
		this.identity = identity;
		this.personality = personality;
		this.needs = needs;
	}

	// -- basic accessors -------------------------------------------------

	public Identity identity() {
		return identity;
	}

	public Personality personality() {
		return personality;
	}

	public Needs needs() {
		return needs;
	}

	public MemoryStream memories() {
		return memories;
	}

	public Relationships relationships() {
		return relationships;
	}

	public List<Belief> beliefs() {
		return List.copyOf(beliefs);
	}

	public List<Goal> goals() {
		return List.copyOf(goals);
	}

	public List<Possession> possessions() {
		return List.copyOf(possessions);
	}

	public List<DecisionTrace> recentDecisions() {
		return List.copyOf(recentDecisions);
	}

	public boolean isAlive() {
		return alive;
	}

	public void markDead() {
		this.alive = false;
	}

	// -- perception / communication (the only ways facts enter the mind) --

	/** Records something this agent directly observed. */
	public MemoryEntry perceive(long tick, String description, double importance, Set<UUID> participants) {
		return memories.addPerceived(tick, description, importance, participants);
	}

	/**
	 * Records something another agent told this agent. {@code tellerId} and
	 * {@code tellerDisplayName} identify who told it (plain data, not a
	 * reference to the teller's mind); {@code tellerSourceMemory} is the
	 * teller's own memory being shared (also plain data). A brand-new
	 * {@link MemoryEntry} is created here, with its own id/timestamp — the
	 * two agents never share a memory object.
	 */
	public MemoryEntry receiveTold(long tick, UUID tellerId, String tellerDisplayName, MemoryEntry tellerSourceMemory) {
		String description = tellerDisplayName + " told me: " + tellerSourceMemory.description();
		double importance = Math.max(0.1, tellerSourceMemory.importance() * 0.85);
		MemoryEntry created = memories.addTold(tick, description, importance,
				Set.of(tellerId), tellerId, tellerSourceMemory.id());
		relationships.with(tellerId).recordToldSomething(tick);
		return created;
	}

	/** Records a conclusion this agent worked out itself, e.g. from a reasoning pass. */
	public MemoryEntry inferMemory(long tick, String description, double importance, long sourceMemoryId) {
		return memories.addInferred(tick, description, importance, Set.of(), sourceMemoryId);
	}

	public Belief formBelief(long tick, String statement, double confidence, Provenance provenance) {
		Belief belief = new Belief(nextBeliefId++, statement, confidence, provenance, tick);
		beliefs.add(belief);
		return belief;
	}

	public Goal addGoal(long tick, String description, double priority, IntentType relatedIntent) {
		Goal goal = new Goal(nextGoalId++, description, priority, relatedIntent, tick, true);
		goals.add(goal);
		return goal;
	}

	public void deactivateGoal(long goalId) {
		for (int i = 0; i < goals.size(); i++) {
			if (goals.get(i).id() == goalId) {
				goals.set(i, goals.get(i).deactivated());
				return;
			}
		}
	}

	public void addPossession(Possession possession) {
		possessions.add(possession);
	}

	// -- persistence support (plain data in, plain data out) --------------

	public long nextBeliefIdPeek() {
		return nextBeliefId;
	}

	public long nextGoalIdPeek() {
		return nextGoalId;
	}

	public void restoreBeliefs(List<Belief> restored, long nextIdValue) {
		beliefs.clear();
		beliefs.addAll(restored);
		this.nextBeliefId = nextIdValue;
	}

	public void restoreGoals(List<Goal> restored, long nextIdValue) {
		goals.clear();
		goals.addAll(restored);
		this.nextGoalId = nextIdValue;
	}

	public void restorePossessions(List<Possession> restored) {
		possessions.clear();
		possessions.addAll(restored);
	}

	// -- fast-system decision making --------------------------------------

	/**
	 * Scores every intent the embodiment layer says is currently physically
	 * available (e.g. there's food nearby, there's another agent nearby) and
	 * picks the best one, given this mind's own needs/personality/goals/
	 * memories. The embodiment layer decides <em>what's possible</em>; the
	 * mind decides <em>what's wanted</em>.
	 */
	public DecisionTrace decide(long tick, Set<IntentType> availableIntents) {
		Set<IntentType> candidates = availableIntents.isEmpty()
				? EnumSet.of(IntentType.IDLE)
				: EnumSet.copyOf(availableIntents);

		List<MemoryEntry> relevantMemories = memories.retrieve(tick, 5);
		Random noise = new Random(identity.id().hashCode() * 31L + tick);

		List<DecisionTrace.ScoredIntent> scored = new ArrayList<>();
		Map<IntentType, List<Cause>> causesByIntent = new LinkedHashMap<>();

		for (IntentType type : candidates) {
			ScoreResult result = score(type, relevantMemories, noise);
			scored.add(new DecisionTrace.ScoredIntent(type, result.score, result.factors));
			causesByIntent.put(type, result.causes);
		}

		scored.sort(Comparator.comparingDouble(DecisionTrace.ScoredIntent::score).reversed());
		IntentType chosen = scored.get(0).intent();

		DecisionTrace trace = new DecisionTrace(nextDecisionId++, tick, scored, chosen, causesByIntent.get(chosen));
		recentDecisions.addLast(trace);
		while (recentDecisions.size() > MAX_RECENT_DECISIONS) {
			recentDecisions.removeFirst();
		}
		return trace;
	}

	private record ScoreResult(double score, Map<String, Double> factors, List<Cause> causes) {
	}

	private ScoreResult score(IntentType type, List<MemoryEntry> relevantMemories, Random noise) {
		Map<String, Double> factors = new LinkedHashMap<>();
		List<Cause> causes = new ArrayList<>();
		double goalBonus = goalBonusFor(type);
		if (goalBonus > 0) {
			factors.put("active goal", goalBonus);
		}
		double jitter = (noise.nextDouble() - 0.5) * 0.05;
		factors.put("jitter", jitter);

		double base = switch (type) {
			case FORAGE_FOOD -> {
				double hunger = 1.0 - needs.food();
				factors.put("hunger", hunger);
				causes.add(Cause.needState("food", needs.food()));
				yield hunger;
			}
			case SEEK_SAFETY -> {
				double danger = (1.0 - needs.safety()) * 1.3;
				factors.put("danger", danger);
				causes.add(Cause.needState("safety", needs.safety()));
				yield danger;
			}
			case SOCIALIZE -> {
				double loneliness = (1.0 - needs.social()) * 0.9;
				double sociability = personality.sociability() * 0.3;
				factors.put("loneliness", loneliness);
				factors.put("sociability", sociability);
				causes.add(Cause.needState("social", needs.social()));
				yield loneliness + sociability;
			}
			case EXPLORE -> {
				double curiosity = personality.curiosity() * 0.7;
				double restlessness = (1.0 - needs.belonging()) * 0.1;
				factors.put("curiosity", curiosity);
				factors.put("restlessness", restlessness);
				double memoryPull = 0.0;
				if (!relevantMemories.isEmpty()) {
					MemoryEntry top = relevantMemories.get(0);
					if (top.importance() > 0.6) {
						memoryPull = top.importance() * 0.3;
						factors.put("recent memory", memoryPull);
						causes.add(Cause.memory(top.id(), top.description()));
					}
				}
				yield curiosity + restlessness + memoryPull;
			}
			case REST -> {
				double satisfaction = (needs.food() + needs.safety() + needs.social() + needs.belonging()) / 4.0;
				double restfulness = satisfaction * 0.4;
				factors.put("overall satisfaction", restfulness);
				yield restfulness;
			}
			case IDLE -> {
				factors.put("baseline", 0.05);
				yield 0.05;
			}
		};

		double score = base + goalBonus + jitter;
		return new ScoreResult(score, factors, causes);
	}

	private double goalBonusFor(IntentType type) {
		double best = 0.0;
		for (Goal goal : goals) {
			if (goal.active() && goal.relatedIntent() == type) {
				best = Math.max(best, goal.priority() * 0.5);
			}
		}
		return best;
	}
}
