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
	/** Whether a monster has hurt it in the last few seconds. Not saved. */
	private boolean underAttack;
	/** At the last look: a sword in its pack, health left (0 to 1), and a monster going for someone else close by. Not saved. */
	private boolean armed;
	private double health = 1.0;
	private boolean otherUnderAttack;
	/** Whether someone in sight looks to be starving while this agent has food to spare. Not saved. */
	private boolean someoneStarving;
	/** Whether it is night where the agent is, at the last look. Not saved. */
	private boolean night;
	/** Whether the agent is at home right now (inside or at its door). Not saved. */
	private boolean atHome;
	private Home home;
	private Project project;
	/** A building of its own part-way up (where, and what), so it isn't forgotten across a restart. */
	private Home buildingSite;
	/** Whether it has a building of its own part-way up. Not saved. */
	private boolean building;
	private boolean imaginingDesign;
	/** Whether something worth mining was in sight at the last look. Not saved. */
	private boolean mineableInSight;
	/** Writing it has read, so it isn't read again; the oldest drop off. */
	private final java.util.LinkedHashSet<Long> readDocuments = new java.util.LinkedHashSet<>();
	private static final int MAX_READ_DOCUMENTS = 256;
	/** How much the thing it could write here is worth to others (0 = nothing to write), at the last look. Not saved. */
	private double worthWriting;
	/** Whether writing it would be trying something it has never seen work. Not saved. */
	private boolean writingIsNew;
	/** Meals of food and blocks of building material carried, at the last look. Not saved. */
	private int foodMeals;
	private int buildingBlocks;
	/** About this many meals put by and farming stops feeling urgent. */
	private static final double PLENTY_OF_FOOD = 30;
	/** With a home and this much wood, there's little reason to chop more. */
	private static final int PLENTY_OF_WOOD = 64;
	private final List<KnownDesign> knownDesigns = new ArrayList<>(List.of(KnownDesign.innate(Design.hut())));
	/** What it knows how to make and where materials come from, each with how it learned it. */
	private final RecipeBook recipeBook = new RecipeBook();
	/** Places it knows from experience: fields, trees, water and the like. Saved. */
	private final Places places = new Places();
	private static final int MAX_KNOWN_DESIGNS = 12;
	private static final double STARVING_DAMPING = 0.6;

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

	/**
	 * Records something this agent read, written down where it stands (a
	 * sign). {@code text} is exactly what the writing says, signature and
	 * all; {@code authorId}/{@code authorMemoryId} say whose memory it came
	 * from, if an agent wrote it ({@code null}/{@code -1} otherwise). Each
	 * piece of writing is read once: returns empty if it was read before.
	 */
	public java.util.Optional<MemoryEntry> readWriting(long tick, long documentId, String text, UUID authorId,
			long authorMemoryId, double importance) {
		if (!readDocuments.add(documentId)) {
			return java.util.Optional.empty();
		}
		while (readDocuments.size() > MAX_READ_DOCUMENTS) {
			readDocuments.remove(readDocuments.iterator().next());
		}
		Set<UUID> about = authorId == null || authorId.equals(identity.id()) ? Set.of() : Set.of(authorId);
		return java.util.Optional.of(memories.addRead(tick, "A sign here says: \"" + text + "\"", importance, about,
				documentId, authorId, authorMemoryId));
	}

	/** The writing it has read, by id, oldest first. */
	public Set<Long> readDocuments() {
		return java.util.Collections.unmodifiableSet(readDocuments);
	}

	public void restoreReadDocuments(java.util.Collection<Long> restored) {
		readDocuments.clear();
		readDocuments.addAll(restored);
	}

	/** Records a conclusion this agent worked out itself, e.g. from a reasoning pass. */
	public MemoryEntry inferMemory(long tick, String description, double importance, long sourceMemoryId) {
		return inferMemory(tick, description, importance, Set.of(), sourceMemoryId);
	}

	/** As above, about these agents (so it isn't told back to them as news). */
	public MemoryEntry inferMemory(long tick, String description, double importance, Set<UUID> participants,
			long sourceMemoryId) {
		return memories.addInferred(tick, description, importance, participants, sourceMemoryId);
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
		return addGoal(tick, description, priority, relatedIntent, null, 0);
	}

	/** A goal with a target: to have {@code targetCount} of {@code targetItem} (an item id), or none if null. */
	public Goal addGoal(long tick, String description, double priority, IntentType relatedIntent, String targetItem,
			int targetCount) {
		expireGoals(tick);
		for (int i = 0; i < goals.size(); i++) {
			Goal g = goals.get(i);
			// A new goal of the same kind replaces the old one, except that wanting to make something
			// else doesn't cancel what it's already making: both stay, newest worked on first.
			boolean differentThings = g.hasTarget() && targetItem != null && !targetItem.equals(g.targetItem());
			if (g.active() && relatedIntent != null && relatedIntent == g.relatedIntent() && !differentThings) {
				goals.set(i, g.deactivated());
			}
		}
		Goal goal = new Goal(nextGoalId++, description, priority, relatedIntent, tick, true, 0, targetItem, targetCount);
		goals.add(goal);
		long active = goals.stream().filter(Goal::active).count();
		for (int i = 0; i < goals.size() && active > MAX_ACTIVE_GOALS; i++) {
			if (goals.get(i).active()) {
				Goal dropped = goals.get(i);
				goals.set(i, dropped.deactivated());
				active--;
				if (dropped.hasTarget()) {
					perceive(tick, "I set aside " + dropped.description() + " for now.", 0.2, Set.of());
				}
			}
		}
		trimGoalHistory();
		return goal;
	}

	/** The goal is met (it has what it set out to make) or given up: it stops pulling on decisions. */
	public void finishGoal(long goalId) {
		for (int i = 0; i < goals.size(); i++) {
			if (goals.get(i).id() == goalId && goals.get(i).active()) {
				goals.set(i, goals.get(i).deactivated());
			}
		}
	}

	/** The active goal with a target set most recently, if any. */
	public java.util.Optional<Goal> targetGoal() {
		Goal latest = null;
		for (Goal g : goals) {
			if (g.active() && g.hasTarget() && (latest == null || g.createdTick() >= latest.createdTick())) {
				latest = g;
			}
		}
		return java.util.Optional.ofNullable(latest);
	}

	/** Times an agent acts on a goal before it counts as done (a goal to visit friends isn't a life sentence). */
	private static final int GOAL_DONE_AFTER = 3;

	/**
	 * It just did something toward {@code intent}. The active goal pursued
	 * through it moves on, and after a few times it is done: remembered as
	 * done, and no longer pulls on decisions. Returns the finished goal, if any.
	 */
	public java.util.Optional<Goal> noteGoalProgress(IntentType intent, long tick) {
		for (int i = 0; i < goals.size(); i++) {
			Goal g = goals.get(i);
			// A goal to have something is done when it has it, not after a few tries.
			if (g.active() && g.relatedIntent() == intent && !g.hasTarget()) {
				Goal moved = g.advanced();
				if (moved.progress() >= GOAL_DONE_AFTER) {
					goals.set(i, moved.deactivated());
					// It did the kind of thing the goal was about, not necessarily the thing itself:
					// remembered as effort, and too slight to pass on as news of success.
					perceive(tick, "I spent time on: " + g.description() + ".", 0.35, Set.of());
					return java.util.Optional.of(moved);
				}
				goals.set(i, moved);
				return java.util.Optional.empty();
			}
		}
		return java.util.Optional.empty();
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

	/** The embodiment reports the time of day and whether the agent is at its home. */
	public void noteSurroundings(boolean isNight, boolean isAtHome) {
		night = isNight;
		atHome = isAtHome;
	}

	public boolean isNight() {
		return night;
	}

	public java.util.Optional<Home> home() {
		return java.util.Optional.ofNullable(home);
	}

	/** Moves in: a building this agent finished (or helped finish) is now home. */
	public void setHome(Home newHome) {
		home = newHome;
	}

	/** The embodiment reports whether a building of this agent's is part-way up. */
	public void noteBuilding(boolean building) {
		this.building = building;
	}

	public boolean isBuilding() {
		return building;
	}

	public java.util.Optional<Home> buildingSite() {
		return java.util.Optional.ofNullable(buildingSite);
	}

	public void setBuildingSite(Home site) {
		buildingSite = site;
	}

	public java.util.Optional<Project> project() {
		return java.util.Optional.ofNullable(project);
	}

	/** Agreed to build with someone, learned where, or (null) finished or gave up. */
	public void setProject(Project newProject) {
		project = newProject;
	}

	/** Home was found destroyed (or abandoned). */
	public void loseHome() {
		home = null;
	}

	public Places places() {
		return places;
	}

	public RecipeBook recipeBook() {
		return recipeBook;
	}

	public List<KnownDesign> knownDesigns() {
		return Collections.unmodifiableList(knownDesigns);
	}

	/** Learns a design unless one with the same id is already known. Returns whether it was new. */
	public boolean learnDesign(KnownDesign known) {
		for (KnownDesign k : knownDesigns) {
			if (k.design().id().equals(known.design().id())) {
				return false;
			}
		}
		knownDesigns.add(known);
		while (knownDesigns.size() > MAX_KNOWN_DESIGNS) {
			knownDesigns.remove(1); // never forget the hut at index 0
		}
		return true;
	}

	/**
	 * Which known design to build next: usually its own, unless a design
	 * learned from someone it trusts and likes a lot appeals more; the hut last.
	 */
	public KnownDesign designToBuild() {
		KnownDesign best = knownDesigns.get(0);
		double bestScore = -1;
		for (KnownDesign k : knownDesigns) {
			double score = switch (k.how()) {
				case "designed" -> 0.8;
				// Someone else's idea appeals as much as one trusts and likes them: a close friend's home can win out.
				case "saw", "told" -> 0.3 + (k.sourceId() == null ? 0.0
						: relationships.get(k.sourceId()).map(r -> r.trust() * 0.5 + Math.max(0, r.affinity()) * 0.3).orElse(0.0));
				default -> 0.1;
			};
			// Newer ideas edge out older ones of equal appeal.
			score += k.learnedTick() * 1e-12;
			if (score > bestScore) {
				best = k;
				bestScore = score;
			}
		}
		return best;
	}

	/** Set while its own design is being imagined (an LLM call in flight), so it doesn't start a hut meanwhile. Not saved. */
	public void setImaginingDesign(boolean imagining) {
		imaginingDesign = imagining;
	}

	public boolean isImaginingDesign() {
		return imaginingDesign;
	}

	/** Whether this agent has its own design. */
	public boolean hasOwnDesign() {
		return knownDesigns.stream().anyMatch(k -> k.how().equals("designed"));
	}

	public void restoreDesigns(List<KnownDesign> restored) {
		knownDesigns.clear();
		knownDesigns.add(KnownDesign.innate(Design.hut()));
		for (KnownDesign k : restored) {
			if (!k.design().id().equals("hut")) {
				knownDesigns.add(k);
			}
		}
	}

	/**
	 * The embodiment reports what the agent carries in plain terms: how many
	 * meals' worth of food, and how many blocks' worth of building material.
	 */
	public void noteStock(int foodMeals, int buildingBlocks) {
		this.foodMeals = foodMeals;
		this.buildingBlocks = buildingBlocks;
	}

	/**
	 * The embodiment reports whether there's something worth writing down
	 * here for others ({@code worth} 0 to 1, 0 for nothing), and whether this
	 * agent has never seen writing work ({@code untried}: it would be trying
	 * it to see).
	 */
	public void noteSomethingToWrite(double worth, boolean untried) {
		this.worthWriting = Math.max(0.0, Math.min(1.0, worth));
		this.writingIsNew = untried;
	}

	/** The embodiment reports whether there's ore (or stone it needs) in sight that it could mine. */
	public void noteMineable(boolean inSight) {
		mineableInSight = inSight;
	}

	/** The embodiment reports whether a hostile is in sight right now. */
	public void noteThreat(boolean inSight) {
		threatInSight = inSight;
	}

	/** The embodiment reports whether a monster has hurt it in the last few seconds. */
	public void noteUnderAttack(boolean attacked) {
		underAttack = attacked;
	}

	/**
	 * The embodiment reports what bears on standing to fight: whether it
	 * carries a sword, how much health it has left (0 to 1), and whether a
	 * monster close by is going for someone else.
	 */
	public void noteCombat(boolean armed, double health, boolean otherUnderAttack) {
		this.armed = armed;
		this.health = Math.max(0.0, Math.min(1.0, health));
		this.otherUnderAttack = otherUnderAttack;
	}

	/** The embodiment reports whether someone in sight looks to be starving, and this agent has food to spare. */
	public void noteSomeoneStarving(boolean seen) {
		someoneStarving = seen;
	}

	/** Starving, and its own searches for food keep failing: other people may share. */
	private boolean wouldAskForFood() {
		return needs.food() < URGENT_NEED && failedFoodSearches >= 2;
	}

	/** Homeless and without the wood for the home it would build. */
	private boolean shortOfWoodForHome() {
		return home == null && buildingBlocks < designToBuild().design().solids().size();
	}

	public int foodMeals() {
		return foodMeals;
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
				// Being hurt right now: the cautious want nothing more than to get away; the bold less so.
				double attacked = underAttack ? 1.2 * (1.1 - personality.risk()) : 0.0;
				if (attacked > 0) {
					factors.put("being attacked", attacked);
				}
				// Badly hurt with a monster in sight: get out of there.
				double wounded = threatInSight && health < 0.5 ? (0.5 - health) * 1.5 : 0.0;
				if (wounded > 0) {
					factors.put("badly hurt", wounded);
				}
				yield danger + attacked + wounded;
			}
			case SOCIALIZE -> {
				double loneliness = (1.0 - needs.social()) * 0.9;
				double sociability = personality.sociability() * 0.3;
				factors.put("loneliness", loneliness);
				factors.put("sociability", sociability);
				causes.add(Cause.needState("social", needs.social()));
				// Seeing someone starving with food in its own pack: the kind go over to them.
				double starving = someoneStarving ? 0.3 + personality.sociability() * 0.5 : 0.0;
				if (starving > 0) {
					factors.put("someone looks starving", starving);
				}
				yield loneliness + sociability + starving;
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
				double sleep = 0.0;
				if (atHome && night) {
					// Night, and already home: time to sleep.
					sleep = 0.9 * fedEnoughToSleep();
					factors.put("night at home", sleep);
				}
				yield restfulness + sleep;
			}
			case PURSUE_PLAN -> {
				// Getting on with what it set out to make: the goal itself (below) does most of the pulling.
				double drive = 0.2 + personality.ambition() * 0.2;
				factors.put("something to make", drive);
				yield drive;
			}
			case GO_HOME -> {
				// The pull of home at night fades when hungry: nobody goes to bed starving if they can help it.
				double nightPull = night ? (0.8 + (1.0 - personality.risk()) * 0.3) * fedEnoughToSleep() : 0.0;
				double unsafe = (1.0 - needs.safety()) * 0.4;
				double rootless = (1.0 - needs.belonging()) * 0.3;
				if (nightPull > 0) {
					factors.put("night is falling", nightPull);
				}
				factors.put("feels unsafe", unsafe);
				factors.put("misses home", rootless);
				causes.add(Cause.needState("safety", needs.safety()));
				yield nightPull + unsafe + rootless;
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
				// Ore in sight is a reason to go and dig, even for an agent that's otherwise content.
				double ore = mineableInSight ? 0.3 + personality.curiosity() * 0.2 : 0.0;
				if (ore > 0) {
					factors.put("something to mine", ore);
				}
				// Homeless, it wants more in hand before building, but not without end.
				double enoughWood = buildingBlocks >= (home != null ? PLENTY_OF_WOOD : PLENTY_OF_WOOD * 2) ? -0.5 : 0.0;
				if (enoughWood < 0) {
					factors.put("plenty of wood already", enoughWood);
				}
				// No roof of its own and not the wood for one: the more rootless it feels, the more that matters.
				double homeless = shortOfWoodForHome() ? 0.25 + (1.0 - needs.belonging()) * 0.3 : 0.0;
				if (homeless > 0) {
					factors.put("no home yet", homeless);
				}
				yield drive + shelterUrge + fullPack + ore + enoughWood + homeless;
			}
			case FARM -> {
				// Planting pays off later, so it appeals to the ambitious and, above all,
				// to anyone whose searches for food keep coming back empty.
				double hunger = (1.0 - needs.food()) * 0.4;
				double foresight = 0.1 + personality.ambition() * 0.3;
				// A full larder takes the urgency out of it.
				double stocked = Math.min(1.0, foodMeals / PLENTY_OF_FOOD);
				double scarcity = 0.15 * Math.min(failedFoodSearches, 4) * (1.0 - stocked);
				double plenty = -0.6 * stocked;
				factors.put("hunger", hunger);
				factors.put("foresight", foresight);
				if (scarcity > 0) {
					factors.put("food is scarce", scarcity);
				}
				if (plenty < 0) {
					factors.put("plenty put by", plenty);
				}
				causes.add(Cause.needState("food", needs.food()));
				yield hunger + foresight + scarcity + plenty;
			}
			case FIGHT -> {
				// Only offered with a monster close. Who stands and fights is a matter of nerve,
				// what's in hand, how hurt it is, and whether someone else needs the help.
				double nerve = personality.risk() * 0.6;
				double weapon = armed ? 0.25 : 0.0;
				double cornered = underAttack ? 0.5 + personality.risk() * 0.6 : 0.0;
				double helping = otherUnderAttack ? 0.2 + personality.sociability() * 0.4 : 0.0;
				double hurt = health < 0.5 ? -(0.5 - health) * 2.0 : 0.0;
				factors.put("nerve", nerve);
				if (weapon > 0) {
					factors.put("has a sword", weapon);
				}
				if (cornered > 0) {
					factors.put("fighting back", cornered);
				}
				if (helping > 0) {
					factors.put("someone needs help", helping);
				}
				if (hurt < 0) {
					factors.put("badly hurt", hurt);
				}
				causes.add(Cause.needState("safety", needs.safety()));
				yield nerve + weapon + cornered + helping + hurt;
			}
			case WRITE_SIGN -> {
				// Only offered with something worth leaving word about. The sociable care
				// more that others know; trying it for the first time is a leap.
				double worth = worthWriting * (0.4 + personality.sociability() * 0.5);
				factors.put("worth others knowing", worth);
				double untried = writingIsNew ? -0.15 + personality.curiosity() * 0.15 : 0.0;
				if (untried != 0) {
					factors.put("never seen it done", untried);
				}
				yield worth + untried;
			}
			case BUILD_SHELTER -> {
				double exposure = (1.0 - needs.safety()) * 1.0;
				double drive = 0.2 + personality.ambition() * 0.3;
				factors.put("exposed", exposure);
				factors.put("ambition", drive);
				causes.add(Cause.needState("safety", needs.safety()));
				double homeless = home == null ? 0.3 : 0.0;
				if (homeless > 0) {
					factors.put("no home yet", homeless);
				}
				yield exposure + drive + homeless;
			}
		};

		if (needs.food() < URGENT_NEED && base > 0 && (type == IntentType.SOCIALIZE && !wouldAskForFood() || type == IntentType.EXPLORE
				|| type == IntentType.REST || type == IntentType.IDLE || type == IntentType.GATHER_MATERIALS
				|| type == IntentType.BUILD_SHELTER || type == IntentType.PURSUE_PLAN || type == IntentType.WRITE_SIGN)) {
			// Starving: everything that doesn't put food in the stomach can wait.
			double damped = base * STARVING_DAMPING;
			factors.put("starving, other things can wait", damped - base);
			base = damped;
		}
		if (type == IntentType.SOCIALIZE && wouldAskForFood()) {
			// Its own searches keep failing: going to people, who may have food to share, is another way to eat.
			double ask = 0.5;
			factors.put("hoping someone will share food", ask);
			causes.add(Cause.needState("food", needs.food()));
			base += ask;
		}
		double score = base + goalBonus + jitter;
		return new ScoreResult(score, factors, causes);
	}

	private double fedEnoughToSleep() {
		return Math.min(1.0, needs.food() / 0.5);
	}

	private double goalBonusFor(IntentType type) {
		// Survival first: while food or safety is urgent, goals only add weight to
		// what addresses it, so a plan can never talk an agent out of eating.
		boolean hungry = needs.food() < URGENT_NEED;
		boolean unsafe = needs.safety() < URGENT_NEED;
		if (hungry || unsafe) {
			boolean addresses = (hungry && (type == IntentType.FORAGE_FOOD || type == IntentType.FARM
					|| type == IntentType.SOCIALIZE && wouldAskForFood()))
					|| (unsafe && (type == IntentType.SEEK_SAFETY || type == IntentType.FIGHT || type == IntentType.GO_HOME || type == IntentType.GATHER_MATERIALS
							|| type == IntentType.BUILD_SHELTER));
			if (!addresses) {
				return 0.0;
			}
		}
		double best = 0.0;
		for (Goal goal : goals) {
			if (goal.active() && goal.relatedIntent() == type) {
				best = Math.max(best, goal.priority() * 0.5);
			} else if (goal.active() && goal.relatedIntent() == IntentType.BUILD_SHELTER
					&& type == IntentType.GATHER_MATERIALS && shortOfWoodForHome()) {
				// Meaning to build, without the wood for it: getting wood is the step towards it.
				best = Math.max(best, goal.priority() * 0.4);
			}
		}
		return best;
	}
}
