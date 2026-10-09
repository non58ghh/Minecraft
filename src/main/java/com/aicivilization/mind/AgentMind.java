package com.aicivilization.mind;

import com.aicivilization.events.Cause;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
	/** A goal fades after about one Minecraft day unless the agent forms it again. */
	static final long GOAL_LIFETIME_TICKS = 24000;
	static final int MAX_ACTIVE_GOALS = 3;
	/** Finished goals kept for the record (shown on the observer). */
	private static final int MAX_GOAL_HISTORY = 20;
	private static final int MAX_BELIEFS = 60;
	/** Below this, food or safety is urgent: goals stop pulling toward anything else. */
	public static final double URGENT_NEED = 0.3;
	/** Food searches in a row that came up empty; makes growing food look better. Not saved. */
	private int failedFoodSearches;
	/** Whether a hostile was in sight at the last look. Not saved. */
	private boolean threatInSight;

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
		return Collections.unmodifiableList(beliefs);
	}

	public List<Goal> goals() {
		return Collections.unmodifiableList(goals);
	}

	public List<Possession> possessions() {
		return Collections.unmodifiableList(possessions);
	}

	public List<DecisionTrace> recentDecisions() {
		return new ArrayList<>(recentDecisions);
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

	/**
	 * Records a belief, unless it repeats one of the agent's recent beliefs
	 * almost word for word (returns null then): reasoning passes tend to
	 * restate the same conclusion, which adds nothing.
	 */
	public Belief formBelief(long tick, String statement, double confidence, Provenance provenance) {
		for (int i = beliefs.size() - 1; i >= Math.max(0, beliefs.size() - 10); i--) {
			if (TextSimilarity.similar(beliefs.get(i).statement(), statement)) {
				return null;
			}
		}
		Belief belief = new Belief(nextBeliefId++, statement, confidence, provenance, tick);
		beliefs.add(belief);
		while (beliefs.size() > MAX_BELIEFS) {
			beliefs.remove(0);
		}
		return belief;
	}

	/**
	 * Adopts a goal. It replaces any active goal pursued through the same
	 * intent (the agent updated its plan), and only the newest
	 * {@link #MAX_ACTIVE_GOALS} stay active.
	 */
	public Goal addGoal(long tick, String description, double priority, IntentType relatedIntent) {
		expireGoals(tick);
		for (int i = 0; i < goals.size(); i++) {
			Goal g = goals.get(i);
			if (g.active() && relatedIntent != null && relatedIntent == g.relatedIntent()) {
				goals.set(i, g.deactivated());
			}
		}
		Goal goal = new Goal(nextGoalId++, description, priority, relatedIntent, tick, true);
		goals.add(goal);
		long active = goals.stream().filter(Goal::active).count();
		for (int i = 0; i < goals.size() && active > MAX_ACTIVE_GOALS; i++) {
			if (goals.get(i).active()) {
				goals.set(i, goals.get(i).deactivated());
				active--;
			}
		}
		trimGoalHistory();
		return goal;
	}

	/** Deactivates goals older than {@link #GOAL_LIFETIME_TICKS}. */
	public void expireGoals(long tick) {
		for (int i = 0; i < goals.size(); i++) {
			Goal g = goals.get(i);
			if (g.active() && tick - g.createdTick() > GOAL_LIFETIME_TICKS) {
				goals.set(i, g.deactivated());
			}
		}
	}

	private void trimGoalHistory() {
		long inactive = goals.stream().filter(g -> !g.active()).count();
		for (int i = 0; i < goals.size() && inactive > MAX_GOAL_HISTORY; ) {
			if (!goals.get(i).active()) {
				goals.remove(i);
				inactive--;
			} else {
				i++;
			}
		}
	}

	/** The embodiment reports whether a search for food found any (a kill, a harvest). */
	public void noteFoodSearch(boolean foundFood) {
		failedFoodSearches = foundFood ? 0 : Math.min(failedFoodSearches + 1, 10);
	}

	/** The embodiment reports whether a hostile is in sight right now. */
	public void noteThreat(boolean inSight) {
		threatInSight = inSight;
	}

	public int failedFoodSearches() {
		return failedFoodSearches;
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

	// -- inventory ---------------------------------------------------------
	// Plain data only: an item is an id string and a count. What an item
	// *is* (food, building block) is decided by the embodiment layer.

	/** Adds {@code quantity} of {@code itemId}, merging with an existing stack. */
	public void receiveItem(long tick, String itemId, int quantity) {
		if (quantity <= 0) {
			return;
		}
		for (int i = 0; i < possessions.size(); i++) {
			Possession existing = possessions.get(i);
			if (existing.itemId().equals(itemId)) {
				possessions.set(i, new Possession(itemId, existing.quantity() + quantity, existing.acquiredTick()));
				return;
			}
		}
		possessions.add(new Possession(itemId, quantity, tick));
	}

	/** Removes {@code quantity} of {@code itemId} if the agent has that many; returns whether it did. */
	public boolean takeItem(String itemId, int quantity) {
		if (quantity <= 0) {
			return true;
		}
		for (int i = 0; i < possessions.size(); i++) {
			Possession existing = possessions.get(i);
			if (existing.itemId().equals(itemId)) {
				if (existing.quantity() < quantity) {
					return false;
				}
				int left = existing.quantity() - quantity;
				if (left == 0) {
					possessions.remove(i);
				} else {
					possessions.set(i, new Possession(itemId, left, existing.acquiredTick()));
				}
				return true;
			}
		}
		return false;
	}

	public int countOf(String itemId) {
		for (Possession possession : possessions) {
			if (possession.itemId().equals(itemId)) {
				return possession.quantity();
			}
		}
		return 0;
	}

	public int totalItems() {
		int total = 0;
		for (Possession possession : possessions) {
			total += possession.quantity();
		}
		return total;
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

		expireGoals(tick);
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
				// Running from a monster in sight beats everything; a vague sense of
				// danger with nothing there shouldn't outrank an empty stomach.
				double danger = (1.0 - needs.safety()) * (threatInSight ? 1.3 : 0.6);
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
			case GATHER_MATERIALS -> {
				double drive = 0.1 + personality.ambition() * 0.4;
				double shelterUrge = (1.0 - needs.safety()) * 0.5;
				// A full pack is a reason to stop collecting and start using it.
				double fullPack = -0.01 * Math.min(totalItems(), 30);
				factors.put("ambition", drive);
				factors.put("want a safer place", shelterUrge);
				factors.put("pack is full", fullPack);
				causes.add(Cause.needState("safety", needs.safety()));
				yield drive + shelterUrge + fullPack;
			}
			case FARM -> {
				// Planting pays off later, so it appeals to the ambitious and, above all,
				// to anyone whose searches for food keep coming back empty.
				double hunger = (1.0 - needs.food()) * 0.4;
				double foresight = 0.1 + personality.ambition() * 0.3;
				double scarcity = 0.15 * Math.min(failedFoodSearches, 4);
				factors.put("hunger", hunger);
				factors.put("foresight", foresight);
				if (scarcity > 0) {
					factors.put("food is scarce", scarcity);
				}
				causes.add(Cause.needState("food", needs.food()));
				yield hunger + foresight + scarcity;
			}
			case BUILD_SHELTER -> {
				double exposure = (1.0 - needs.safety()) * 1.0;
				double drive = 0.2 + personality.ambition() * 0.3;
				factors.put("exposed", exposure);
				factors.put("ambition", drive);
				causes.add(Cause.needState("safety", needs.safety()));
				yield exposure + drive;
			}
		};

		double score = base + goalBonus + jitter;
		return new ScoreResult(score, factors, causes);
	}

	private double goalBonusFor(IntentType type) {
		// Survival first: while food or safety is urgent, goals only add weight to
		// what addresses it, so a plan can never talk an agent out of eating.
		boolean hungry = needs.food() < URGENT_NEED;
		boolean unsafe = needs.safety() < URGENT_NEED;
		if (hungry || unsafe) {
			boolean addresses = (hungry && (type == IntentType.FORAGE_FOOD || type == IntentType.FARM))
					|| (unsafe && (type == IntentType.SEEK_SAFETY || type == IntentType.GATHER_MATERIALS
							|| type == IntentType.BUILD_SHELTER));
			if (!addresses) {
				return 0.0;
			}
		}
		double best = 0.0;
		for (Goal goal : goals) {
			if (goal.active() && goal.relatedIntent() == type) {
				best = Math.max(best, goal.priority() * 0.5);
			}
		}
		return best;
	}
}
