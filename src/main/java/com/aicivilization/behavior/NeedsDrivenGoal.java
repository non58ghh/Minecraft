package com.aicivilization.behavior;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.action.FoodActions;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.DecisionTrace;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.Needs;
import com.aicivilization.perception.PerceptionSystem;
import com.aicivilization.perception.Surroundings;
import com.aicivilization.population.ActivityTier;
import com.aicivilization.population.PopulationRegistry;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The "fast system": a plain utility AI, no LLM involved. Every tick it
 * re-perceives the world; every few seconds (see {@link DecisionPacing}) it
 * asks the agent's mind to score the intents currently physically available,
 * and drives whatever it chose. This is the only {@code Goal} the agent
 * needs — it subsumes moving, foraging, fleeing, socializing, and idling.
 */
public final class NeedsDrivenGoal extends Goal {

	private static final PerceptionSystem PERCEPTION = new PerceptionSystem();
	private static final double MOVE_SPEED = 0.6;
	private static final double ARRIVE_DISTANCE_SQ = 4.0;
	/** Close enough to chop a log or place a block (3 blocks). */
	private static final double ACT_DISTANCE_SQ = 9.0;
	/** Close enough to land a blow on an animal. */
	private static final double ATTACK_DISTANCE_SQ = 5.0;
	private static final long ATTACK_INTERVAL_TICKS = 12;
	/** An agent that hasn't reached its goal after 20 seconds gives up on it. */
	private static final long TASK_TIMEOUT_TICKS = 400;
	private static final double CHASE_SPEED = 0.9;
	private static final long EAT_CHECK_INTERVAL_TICKS = 20;
	private static final long DECISION_INTERVAL_TICKS = 60;
	/** A well-fed agent heals one point of health this often. */
	private static final long HEAL_INTERVAL_TICKS = 1200;
	/** Below this food level, foraging is always an option. */
	private static final double HUNGRY = 0.6;
	/** How far a hungry agent wanders to search when no animal is in sight. */
	private static final double FOOD_SEARCH_RADIUS = 32;
	/** Soonest a finished task can trigger the next decision. */
	private static final long MIN_DECISION_GAP_TICKS = 20;

	private final AgentEntity entity;
	private final Set<UUID> knownAgentIds = new HashSet<>();

	private IntentType currentIntent = IntentType.IDLE;
	private Vec3 moveTarget;
	private Entity socialTarget;
	private Animal huntTarget;
	private BlockPos gatherTarget;
	/** The current move target is a wander toward a random far point. */
	private boolean wandering;
	/** A food job at a spot: harvest, plant, cut grass for seeds, or feed a pair of animals. */
	private enum FoodTask { HARVEST, PLANT, CUT_GRASS, BREED, TEND }
	private FoodTask foodTask;
	private BlockPos foodTarget;
	private FoodActions.BreedPair breedPair;
	/** Where this agent has planted, newest last, so it can go back to tend and harvest. Not saved. */
	private final java.util.ArrayDeque<BlockPos> myFields = new java.util.ArrayDeque<>();
	private static final int MAX_REMEMBERED_FIELDS = 8;
	/** How often an agent goes back to look after its fields while farming. */
	private static final long TEND_INTERVAL_TICKS = 1200;
	private long lastTendTick = Long.MIN_VALUE / 2;
	/** The building this agent is part-way through (or repairing), if any, and its design. */
	private BlockPos shelterOrigin;
	private Design shelterDesign;
	/** Night, by the overworld clock: from dusk to just before dawn. */
	private static final long NIGHT_START = 12500;
	private static final long NIGHT_END = 23500;
	/** Within this of its bed, an agent counts as at home. */
	private static final double AT_HOME_DISTANCE_SQ = 9.0;
	/** A home with more than this share of its blocks missing is a ruin, not a home. */
	private static final double RUINED = 1.0 / 3.0;
	/** Wanting to go somewhere and not getting two blocks for this long means stuck. */
	private static final int STRANDED_TICKS = 600;
	private Vec3 strandedAnchor;
	private int strandedTicks;
	private int scrambleBackoff = 1;
	private static final int MAX_SCRAMBLE_BACKOFF = 32;
	private long taskStartTick = 0;
	private long lastAttackTick = 0;
	private final DecisionPacing pacing = new DecisionPacing(DECISION_INTERVAL_TICKS, MIN_DECISION_GAP_TICKS);
	private boolean wasInCrisis = false;

	public NeedsDrivenGoal(AgentEntity entity) {
		this.entity = entity;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return entity.level() instanceof ServerLevel;
	}

	@Override
	public boolean canContinueToUse() {
		return true;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (!(entity.level() instanceof ServerLevel world)) {
			return;
		}
		AgentMind mind = entity.mind();
		if (mind == null || !mind.isAlive()) {
			return;
		}
		if (!AICivilizationMod.isSimulationRunning()) {
			// Switched off with /civ off, or outside active hours: stand still, and let needs and decisions wait.
			entity.getNavigation().stop();
			return;
		}

		long tick = world.getGameTime();
		PopulationRegistry registry = PopulationRegistry.get(world);
		// Gate on the population scheduler, not just an ad-hoc check: with a
		// handful of agents this tier is always ACTIVE (every tick), but the
		// gate is real, so ACTIVE/IDLE/DORMANT tiers can be wired in later
		// without touching this call site.
		if (!registry.scheduler().shouldUpdate(entity.getUUID(), tick, ActivityTier.ACTIVE)) {
			return;
		}

		mind.needs().decay(1);

		EventLog log = EventLog.get(world);
		Surroundings surroundings = PERCEPTION.perceive(entity, world);
		noteFirstSightings(mind, surroundings, tick);
		noteCrisis(mind, log, tick);
		if (tick % EAT_CHECK_INTERVAL_TICKS == 0
				&& !PhysicalActions.tryEat(entity, mind, tick, log)
				&& FoodActions.bakeBread(mind, tick)) {
			PhysicalActions.tryEat(entity, mind, tick, log);
		}
		applyHungerToHealth(mind, world, tick);

		if (pacing.shouldDecide(tick)) {
			decideAndAct(mind, surroundings, tick, world, log);
			pacing.onDecided(tick);
		}

		pursueCurrentTarget(mind, tick, world, log);
	}

	/**
	 * Real stakes: an agent with nothing in its stomach loses health slowly
	 * and can starve to death; a well-fed one heals. The interval is a
	 * setting (0 turns starvation off).
	 */
	private void applyHungerToHealth(AgentMind mind, ServerLevel world, long tick) {
		long starveEvery = AICivilizationMod.starvationIntervalTicks();
		if (starveEvery > 0 && tick % starveEvery == 0 && mind.needs().food() <= 0.0) {
			entity.hurtServer(world, entity.damageSources().starve(), 1.0f);
		} else if (tick % HEAL_INTERVAL_TICKS == 0 && mind.needs().food() >= 0.5
				&& entity.getHealth() < entity.getMaxHealth()) {
			entity.heal(1.0f);
		}
	}

	private void noteFirstSightings(AgentMind mind, Surroundings surroundings, long tick) {
		for (Surroundings.OtherAgentSighting sighting : surroundings.nearbyAgents()) {
			if (knownAgentIds.add(sighting.agentId()) && mind.relationships().get(sighting.agentId()).isEmpty()) {
				mind.relationships().with(sighting.agentId());
				mind.perceive(tick, "I saw " + sighting.displayName() + " for the first time.", 0.5,
						Set.of(sighting.agentId()));
			}
		}
	}

	private void noteCrisis(AgentMind mind, EventLog log, long tick) {
		boolean inCrisis = mind.needs().hasCrisis();
		if (inCrisis && !wasInCrisis) {
			String needName = mind.needs().lowestName();
			log.append(tick, EventType.NEED_CRISIS, List.of(mind.identity().id()),
					mind.identity().name() + " is in crisis: " + needName + " is critically low.",
					List.of(Cause.needState(needName, mind.needs().lowestValue())));
		}
		wasInCrisis = inCrisis;
	}

	private void decideAndAct(AgentMind mind, Surroundings surroundings, long tick, ServerLevel world, EventLog log) {
		// The perception system reports what's possible nearby; what the world
		// offers for gathering and building is looked up here, once per decision.
		Optional<Home> home = mind.home();
		long timeOfDay = world.getOverworldClockTime() % 24000L;
		boolean night = timeOfDay >= NIGHT_START && timeOfDay < NIGHT_END;
		boolean atHome = home.isPresent()
				&& entity.position().distanceToSqr(PhysicalActions.bedSpot(homeOrigin(home.get()), home.get().design())) <= AT_HOME_DISTANCE_SQ;
		mind.noteSurroundings(night, atHome);
		if (shelterOrigin == null) {
			// Not building anything yet: the next building would be the design it likes best.
			shelterDesign = mind.designToBuild().design();
		}
		// With a home, an agent only builds to repair it; without one, it looks for a site once it knows what to build.
		PhysicalActions.Opportunities opportunities = PhysicalActions.scan(entity, world, mind, shelterOrigin, shelterDesign,
				home.isEmpty() && mind.hasOwnDesign() && !mind.isImaginingDesign());
		Set<IntentType> available = EnumSet.copyOf(surroundings.availableIntents());
		// A hungry agent can always go looking for food, not only when an animal is
		// already in sight; likewise an agent that feels unsafe can look for cover.
		if (mind.needs().food() < HUNGRY) {
			available.add(IntentType.FORAGE_FOOD);
		}
		if (mind.needs().safety() < AgentMind.URGENT_NEED) {
			available.add(IntentType.SEEK_SAFETY);
		}
		BlockPos fieldAnchor = myFields.isEmpty() || nearestField().distSqr(entity.blockPosition()) > 32 * 32
				? null : nearestField();
		if (fieldAnchor == null && home.isPresent() && homeOrigin(home.get()).distSqr(entity.blockPosition()) <= 32 * 32) {
			// No field of its own yet: farm near home.
			fieldAnchor = homeOrigin(home.get());
		}
		FoodActions.FoodOpportunities food = FoodActions.scan(entity, world, mind, fieldAnchor);
		if (food.ripePlant().isPresent()) {
			available.add(IntentType.FORAGE_FOOD);
		}
		if (food.anyFarming()) {
			available.add(IntentType.FARM);
		}
		if (opportunities.log().isPresent()) {
			available.add(IntentType.GATHER_MATERIALS);
		}
		if (opportunities.shelterSite().isPresent()) {
			available.add(IntentType.BUILD_SHELTER);
		}
		if (home.isPresent() && !atHome) {
			available.add(IntentType.GO_HOME);
		}
		if (atHome) {
			available.add(IntentType.REST);
		}
		// A wander toward a far point (searching, exploring) is kept across
		// decisions while the agent still wants the same thing, so it actually
		// gets somewhere instead of picking a new spot every few seconds.
		IntentType previousIntent = currentIntent;
		Vec3 previousWander = wandering ? moveTarget : null;
		long previousStart = taskStartTick;
		mind.noteThreat(surroundings.nearestHostile().isPresent());
		DecisionTrace trace = mind.decide(tick, available);
		currentIntent = trace.chosen();
		wandering = false;
		moveTarget = null;
		socialTarget = null;
		huntTarget = null;
		gatherTarget = null;
		foodTask = null;
		foodTarget = null;
		breedPair = null;
		taskStartTick = tick;

		if (pacing.shouldLog(currentIntent)) {
			log.append(tick, EventType.DECISION, List.of(mind.identity().id()),
					mind.identity().name() + " decided to " + describeIntent(currentIntent) + ".",
					trace.causes());
		}

		switch (currentIntent) {
			case FORAGE_FOOD -> {
				if (surroundings.nearestAnimal().isPresent()) {
					huntTarget = surroundings.nearestAnimal().get();
				} else if (food.ripePlant().isPresent()) {
					setFoodTask(FoodTask.HARVEST, food.ripePlant().get());
				} else {
					moveTarget = randomNearbyPoint(Math.min(FOOD_SEARCH_RADIUS + 16 * mind.failedFoodSearches(), 96));
					wandering = true;
				}
			}
			case FARM -> {
				if (food.ripePlant().isPresent()) {
					setFoodTask(FoodTask.HARVEST, food.ripePlant().get());
				} else if (!myFields.isEmpty() && tick - lastTendTick > TEND_INTERVAL_TICKS) {
					// Look after what's already planted before planting more.
					setFoodTask(FoodTask.TEND, nearestField().above());
				} else if (food.plot().isPresent()) {
					setFoodTask(FoodTask.PLANT, food.plot().get());
				} else if (food.breedPair().isPresent()) {
					breedPair = food.breedPair().get();
					foodTask = FoodTask.BREED;
					moveTarget = breedPair.a().position();
				} else if (food.seedGrass().isPresent()) {
					setFoodTask(FoodTask.CUT_GRASS, food.seedGrass().get());
				} else if (!myFields.isEmpty()) {
					// Go back to a field planted earlier and tend it (harvesting it once it's ready).
					setFoodTask(FoodTask.TEND, nearestField().above());
				} else {
					moveTarget = randomNearbyPoint(16);
					wandering = true;
				}
			}
			case GATHER_MATERIALS -> opportunities.log().ifPresentOrElse(pos -> {
				gatherTarget = pos;
				moveTarget = Vec3.atCenterOf(pos);
			}, () -> moveTarget = randomNearbyPoint(10));
			case BUILD_SHELTER -> opportunities.shelterSite().ifPresent(origin -> {
				shelterOrigin = origin;
				moveTarget = PhysicalActions.standingSpot(origin, shelterDesign);
			});
			case GO_HOME -> home.ifPresent(h -> moveTarget = PhysicalActions.bedSpot(homeOrigin(h), h.design()));
			case SEEK_SAFETY -> {
				Optional<Monster> hostile = surroundings.nearestHostile();
				if (hostile.isPresent()) {
					Vec3 away = entity.position().subtract(hostile.get().position());
					if (away.lengthSqr() < 0.01) {
						away = new Vec3(1, 0, 0);
					}
					moveTarget = entity.position().add(away.normalize().scale(14));
				} else {
					moveTarget = randomNearbyPoint(8);
				}
			}
			case SOCIALIZE -> {
				if (!surroundings.nearbyAgents().isEmpty()) {
					socialTarget = surroundings.nearbyAgents().get(0).entity();
				} else {
					surroundings.nearestPlayer().ifPresent(player -> socialTarget = player);
				}
			}
			case EXPLORE -> {
				moveTarget = randomNearbyPoint(24);
				wandering = true;
			}
			case REST, IDLE -> {
				// stay put; small passive regen happens in pursueCurrentTarget.
			}
		}
		if (wandering && previousWander != null && currentIntent == previousIntent
				&& tick - previousStart < TASK_TIMEOUT_TICKS) {
			moveTarget = previousWander;
			taskStartTick = previousStart;
		}
		if (entity.isInWater() && huntTarget == null && socialTarget == null
				&& (moveTarget == null || !entity.level().getFluidState(BlockPos.containing(moveTarget)).isEmpty())) {
			// Nobody lives in a lake: whatever it wants, first get back to dry land.
			nearestDryLand(48).ifPresent(shore -> {
				moveTarget = shore;
				wandering = false;
			});
		}
	}

	private BlockPos nearestField() {
		BlockPos here = entity.blockPosition();
		BlockPos best = myFields.peekLast();
		for (BlockPos field : myFields) {
			if (field.distSqr(here) < best.distSqr(here)) {
				best = field;
			}
		}
		return best;
	}

	private void setFoodTask(FoodTask task, BlockPos at) {
		foodTask = task;
		foodTarget = at;
		moveTarget = Vec3.atCenterOf(at);
	}

	private void pursueCurrentTarget(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		if (checkStranded(mind, tick, world, log)) {
			return;
		}
		boolean busy = moveTarget != null || socialTarget != null || huntTarget != null;
		if (busy && tick - taskStartTick > TASK_TIMEOUT_TICKS) {
			if (currentIntent == IntentType.FORAGE_FOOD) {
				mind.noteFoodSearch(false);
			}
			moveTarget = null;
			socialTarget = null;
			huntTarget = null;
			gatherTarget = null;
			entity.getNavigation().stop();
			pacing.onTaskFinished();
			return;
		}

		if (huntTarget != null) {
			if (huntTarget.isDeadOrDying()) {
				PhysicalActions.finishKill(entity, world, mind, huntTarget, tick, log);
				mind.noteFoodSearch(true);
				huntTarget = null;
				pacing.onTaskFinished();
			} else if (huntTarget.isRemoved()) {
				huntTarget = null;
				pacing.onTaskFinished();
			} else if (entity.distanceToSqr(huntTarget) <= ATTACK_DISTANCE_SQ) {
				entity.getNavigation().stop();
				entity.getLookControl().setLookAt(huntTarget);
				if (tick - lastAttackTick >= ATTACK_INTERVAL_TICKS) {
					PhysicalActions.attack(entity, world, huntTarget);
					lastAttackTick = tick;
				}
			} else if (entity.getNavigation().isDone() || tick % 10 == 0) {
				entity.getNavigation().moveTo(huntTarget, CHASE_SPEED);
			}
			return;
		}

		if (moveTarget != null) {
			boolean working = currentIntent == IntentType.GATHER_MATERIALS || currentIntent == IntentType.BUILD_SHELTER
					|| foodTask != null;
			if (entity.position().distanceToSqr(moveTarget) <= (working ? ACT_DISTANCE_SQ : ARRIVE_DISTANCE_SQ)) {
				onArrivedAtLocation(mind, tick, world, log);
				moveTarget = null;
				pacing.onTaskFinished();
			} else if (entity.getNavigation().isDone()) {
				entity.getNavigation().moveTo(moveTarget.x, moveTarget.y, moveTarget.z, MOVE_SPEED);
			}
			return;
		}

		if (socialTarget != null) {
			if (!socialTarget.isAlive() || entity.distanceToSqr(socialTarget) <= ARRIVE_DISTANCE_SQ) {
				if (socialTarget.isAlive()) {
					onArrivedAtSocialTarget(mind, tick, world, log);
				}
				socialTarget = null;
				pacing.onTaskFinished();
			} else if (entity.getNavigation().isDone()) {
				entity.getNavigation().moveTo(socialTarget, MOVE_SPEED);
			}
			return;
		}

		if (currentIntent == IntentType.REST) {
			Needs needs = mind.needs();
			// Resting in one's own home restores far more than resting in the open.
			double homeBonus = mind.home().isPresent()
					&& entity.position().distanceToSqr(PhysicalActions.bedSpot(homeOrigin(mind.home().get()),
							mind.home().get().design())) <= AT_HOME_DISTANCE_SQ ? 3.0 : 1.0;
			needs.adjustSafety(0.001 * homeBonus);
			needs.adjustBelonging(0.0006 * homeBonus);
		}
	}

	private void onArrivedAtLocation(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		scrambleBackoff = 1;
		if (foodTask != null) {
			switch (foodTask) {
				case HARVEST -> {
					if (FoodActions.harvest(entity, world, mind, foodTarget, tick, log) > 0) {
						mind.noteFoodSearch(true);
					}
				}
				case PLANT -> {
					if (FoodActions.plant(entity, world, mind, foodTarget, tick, log)) {
						myFields.remove(foodTarget);
						myFields.addLast(foodTarget);
						while (myFields.size() > MAX_REMEMBERED_FIELDS) {
							myFields.removeFirst();
						}
					}
				}
				case CUT_GRASS -> FoodActions.cutGrass(entity, world, mind, foodTarget, tick);
				case BREED -> FoodActions.breed(entity, mind, breedPair, tick, log);
				case TEND -> {
					FoodActions.tend(entity, world, mind, foodTarget, tick, log);
					lastTendTick = tick;
				}
			}
			foodTask = null;
			foodTarget = null;
			breedPair = null;
			return;
		}
		if (currentIntent == IntentType.FORAGE_FOOD) {
			// Walked out to search and found nothing to hunt or pick on the way.
			mind.noteFoodSearch(false);
		}
		switch (currentIntent) {
			case GATHER_MATERIALS -> {
				if (gatherTarget != null) {
					PhysicalActions.chop(entity, world, mind, gatherTarget, tick, log);
					gatherTarget = null;
				}
			}
			case BUILD_SHELTER -> {
				if (shelterOrigin != null) {
					PhysicalActions.ShelterBuildResult result = PhysicalActions.build(entity, world, mind, shelterOrigin,
							shelterDesign, 4, tick, log);
					if (result.completed) {
						onBuildingFinished(mind, tick, log);
					}
				}
			}
			case GO_HOME -> mind.home().ifPresent(h -> arriveHome(mind, h, tick, world, log));
			case SEEK_SAFETY -> {
				mind.needs().adjustSafety(0.3);
				mind.perceive(tick, "I found a safer spot.", 0.35, Set.of());
			}
			case EXPLORE -> {
				double importance = 0.3 + entity.getRandom().nextDouble() * 0.3;
				mind.perceive(tick, "I explored an unfamiliar area.", importance, Set.of());
			}
			default -> {
			}
		}
	}

	/**
	 * An agent that keeps wanting to go somewhere but hasn't moved two blocks
	 * in half a minute is stuck (on a peak with sheer drops all round, in a
	 * pit or a water hole). It scrambles out. Returns whether it just did.
	 */
	private boolean checkStranded(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		boolean wantsToGo = moveTarget != null || socialTarget != null || huntTarget != null;
		// Sideways movement only: bobbing up and down in a water hole isn't getting anywhere.
		if (strandedAnchor == null || entity.position().subtract(strandedAnchor).horizontalDistanceSqr() > 4.0) {
			strandedAnchor = entity.position();
			strandedTicks = 0;
			return false;
		}
		if (!wantsToGo) {
			return false;
		}
		if (++strandedTicks < STRANDED_TICKS * scrambleBackoff) {
			return false;
		}
		strandedTicks = 0;
		strandedAnchor = null;
		// If scrambling doesn't lead anywhere (it gets stuck again before reaching anything), try less often.
		scrambleBackoff = Math.min(scrambleBackoff * 2, MAX_SCRAMBLE_BACKOFF);
		if (PhysicalActions.scramble(entity, world, mind, tick, log)) {
			moveTarget = null;
			socialTarget = null;
			huntTarget = null;
			pacing.onTaskFinished();
			return true;
		}
		return false;
	}

	private static BlockPos homeOrigin(Home home) {
		return new BlockPos(home.x(), home.y(), home.z());
	}

	/** A building just got its last block: the agent's first one becomes its home. */
	private void onBuildingFinished(AgentMind mind, long tick, EventLog log) {
		BlockPos origin = shelterOrigin;
		Design design = shelterDesign;
		shelterOrigin = null;
		Optional<Home> home = mind.home();
		if (home.isPresent() && homeOrigin(home.get()).equals(origin)) {
			mind.perceive(tick, "I repaired my home.", 0.5, Set.of());
			return;
		}
		if (home.isEmpty()) {
			mind.setHome(new Home(origin.getX(), origin.getY(), origin.getZ(), design, tick));
			mind.needs().adjustBelonging(0.2);
			mind.perceive(tick, "I moved into my new " + design.kind() + ". It is my home now.", 0.85, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " moved into their new " + design.kind() + ".", List.of());
		}
	}

	/** Back home: check it still stands, and start repairs or give it up if not. */
	private void arriveHome(AgentMind mind, Home home, long tick, ServerLevel world, EventLog log) {
		BlockPos origin = homeOrigin(home);
		double damage = PhysicalActions.damage(world, origin, home.design());
		if (damage > RUINED) {
			mind.loseHome();
			mind.needs().adjustBelonging(-0.3);
			mind.perceive(tick, "I came home and found my " + home.design().kind() + " in ruins.", 0.9, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " found their home in ruins and must start again.", List.of());
			if (origin.equals(shelterOrigin)) {
				shelterOrigin = null;
			}
			return;
		}
		if (damage > 0) {
			// Some blocks are missing: set about putting them back.
			shelterOrigin = origin;
			shelterDesign = home.design();
			mind.perceive(tick, "Part of my home has been knocked down; I should repair it.", 0.6, Set.of());
		}
		mind.needs().adjustSafety(0.15);
		mind.needs().adjustBelonging(0.1);
	}

	private void onArrivedAtSocialTarget(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		if (socialTarget instanceof AgentEntity otherAgent) {
			double roll = entity.getRandom().nextDouble();
			ConversationBehavior.attempt(entity, otherAgent, tick, log, roll);
		} else if (socialTarget instanceof Player player) {
			mind.needs().adjustSocial(0.1);
			mind.perceive(tick, "I met a person named " + player.getName().getString() + ".", 0.4,
					Set.of(player.getUUID()));
		}
	}

	/** A random spot some way off, on dry ground where possible (not mid-air, inside a hill or out on a lake). */
	private Vec3 randomNearbyPoint(double radius) {
		Vec3 fallback = null;
		for (int attempt = 0; attempt < 8; attempt++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2;
			double distance = radius * 0.5 + entity.getRandom().nextDouble() * radius * 0.5;
			Vec3 flat = entity.position().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
			if (fallback == null) {
				fallback = flat;
			}
			Optional<Vec3> dry = dryGroundAt((int) Math.floor(flat.x), (int) Math.floor(flat.z));
			if (dry.isPresent()) {
				return dry.get();
			}
		}
		return fallback;
	}

	/** The surface at this column, if it is dry land. */
	private Optional<Vec3> dryGroundAt(int x, int z) {
		Level level = entity.level();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos ground = new BlockPos(x, y - 1, z);
		if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()
				|| !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
			return Optional.empty();
		}
		return Optional.of(new Vec3(x + 0.5, y, z + 0.5));
	}

	/** The nearest dry land, searching outwards in rings. */
	private Optional<Vec3> nearestDryLand(int radius) {
		BlockPos here = entity.blockPosition();
		for (int r = 1; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					Optional<Vec3> dry = dryGroundAt(here.getX() + dx, here.getZ() + dz);
					if (dry.isPresent()) {
						return dry;
					}
				}
			}
		}
		return Optional.empty();
	}

	private static String describeIntent(IntentType type) {
		return switch (type) {
			case FORAGE_FOOD -> "forage for food";
			case SEEK_SAFETY -> "seek safety";
			case SOCIALIZE -> "socialize";
			case EXPLORE -> "explore";
			case REST -> "rest";
			case IDLE -> "do nothing in particular";
			case GATHER_MATERIALS -> "gather materials";
			case BUILD_SHELTER -> "build a shelter";
			case FARM -> "grow food";
			case GO_HOME -> "go home";
		};
	}
}
