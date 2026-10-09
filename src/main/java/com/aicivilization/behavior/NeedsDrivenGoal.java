package com.aicivilization.behavior;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.action.Cooking;
import com.aicivilization.action.Crafting;
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
	private final CaveEscape caveEscape;
	/** Works through goals that name a thing to have. */
	private final PlanRunner planRunner;
	/** A trip down a self-cut staircase for ore. */
	private final DigDown digDown;
	/** Where the current plan step happens (an ore, a furnace). */
	private BlockPos planTarget;
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
	/** Where it last got stuck, to tell real progress from milling about the same spot. */
	private BlockPos lastStuckAt;
	/** Trips home in a row that ended stuck on the way. Not saved: a restart just gives home another chance. */
	private int failedTripsHome;
	/** After this many, the way home is taken to be lost and it settles somewhere new. */
	private static final int GIVE_UP_ON_HOME_AFTER = 3;
	/** Places it found it couldn't reach, and until when to leave them be. Not saved. */
	private final java.util.Map<BlockPos, Long> unreachable = new java.util.HashMap<>();
	private static final long UNREACHABLE_FOR_TICKS = 12000;
	/** Somewhere it got stuck makes everything this close to it look like a bad idea for a while. */
	private static final double UNREACHABLE_RADIUS_SQ = 36;
	private static final long LOOK_AT_HOMES_TICKS = 200;
	/** After this many fruitless scrambles in a row, consider swimming. */
	private static final int SWIM_AFTER_BACKOFF = 4;
	/** How long it allows itself to swim once it decides to. */
	private static final long SWIM_FOR_TICKS = 2400;
	private long swimUntilTick = Long.MIN_VALUE;
	/** How long a shared building can go without a site before the partners give up on it (three days). */
	private static final long PROJECT_PATIENCE_TICKS = 72000;
	/** Lonely enough to go looking for someone it knows. */
	private static final double LONELY = 0.4;
	/** How old a sighting can be and still be worth following. */
	private static final long FRIEND_LEAD_TICKS = 24000;
	/** Who it's walking off to find, if anyone. */
	private UUID seekingFriend;
	private static final int MAX_SCRAMBLE_BACKOFF = 32;
	private long taskStartTick = 0;
	private long lastAttackTick = 0;
	private final DecisionPacing pacing = new DecisionPacing(DECISION_INTERVAL_TICKS, MIN_DECISION_GAP_TICKS);
	private boolean wasInCrisis = false;

	public NeedsDrivenGoal(AgentEntity entity) {
		this.entity = entity;
		this.caveEscape = new CaveEscape(entity);
		this.planRunner = new PlanRunner(entity);
		this.digDown = new DigDown(entity);
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
		if (tick % LOOK_AT_HOMES_TICKS == 0) {
			// A look at the homes of whoever is around: a design can catch on just by being seen.
			for (Surroundings.OtherAgentSighting sighting : surroundings.nearbyAgents()) {
				if (sighting.entity() instanceof AgentEntity other && other.mind() != null) {
					Imitation.lookAt(entity, mind, other.mind(), world, tick, EventLog.get(world));
				}
			}
		}
		noteCrisis(mind, log, tick);
		if (tick % EAT_CHECK_INTERVAL_TICKS == 0
				&& !PhysicalActions.tryEat(entity, mind, tick, log)
				&& FoodActions.bakeBread(mind, tick)) {
			PhysicalActions.tryEat(entity, mind, tick, log);
		}
		applyHungerToHealth(mind, world, tick);
		if (tick % EAT_CHECK_INTERVAL_TICKS == 5) {
			com.aicivilization.world.StartingKnowledge.seedIfEmpty(mind, com.aicivilization.world.RecipeCatalog.get());
			leaveSurplus(mind, tick);
			Crafting.craftWhatsNeeded(mind, mind.home().isPresent(), tick, log);
		}
		if (tick % EAT_CHECK_INTERVAL_TICKS == 10) {
			Cooking.tick(entity, world, mind, tick, log);
		}
		if (entity.isInWall() || entity.isInPowderSnow) {
			// Buried (a block fell on it, it ended up inside one, or it sank into powder snow): climb straight up out.
			BlockPos feet = entity.blockPosition();
			boolean freed = false;
			for (int up = 1; up <= 6; up++) {
				BlockPos free = feet.above(up);
				if (world.getBlockState(free).isAir() && world.getBlockState(free.above()).isAir()
						&& !world.getBlockState(free.below()).isAir()
						&& !world.getBlockState(free.below()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) {
					entity.teleportTo(free.getX() + 0.5, free.getY(), free.getZ() + 0.5);
					freed = true;
					break;
				}
			}
			if (!freed && tick % 20 == 0) {
				// Nothing firm straight above (deep powder snow): make for solid ground to the side.
				PhysicalActions.scramble(entity, world, mind, tick, log);
			}
		}
		if (swimUntilTick != Long.MIN_VALUE && tick >= swimUntilTick && !entity.isInWater()) {
			swimUntilTick = Long.MIN_VALUE;
			stopSwimming();
		}
		// Down a hole with nowhere it can even plan a path to, it would never count as stuck: cut steps out now.
		if (tick % 100 == 37 && !caveEscape.isClimbing() && entity.onGround() && !nearHome(mind)
				&& caveEscape.inPit(world) && caveEscape.startClimb(mind, world, tick, log, 2)) {
			moveTarget = null;
			socialTarget = null;
			huntTarget = null;
			pacing.onTaskFinished();
			return;
		}
		if (digDown.active()) {
			boolean night = world.getOverworldClockTime() % 24000L > 12500L;
			if (digDown.tick(world, mind, tick, log, night)) {
				return;
			}
			// Trip over: back up it goes, as the end of a trip rather than being lost.
			caveEscape.endTrip();
			if (!digDown.lastFound()) {
				planRunner.digFailed(digDown.wantedItem());
			}
		}
		// Underground with something to mine is a trip, not being lost.
		boolean minePurpose = currentIntent == IntentType.GATHER_MATERIALS && gatherTarget != null
				&& PhysicalActions.isMineTarget(world.getBlockState(gatherTarget))
				|| currentIntent == IntentType.PURSUE_PLAN && planTarget != null;
		if (caveEscape.tick(mind, world, tick, log, minePurpose)) {
			// Lost underground: getting out comes before anything else it might want.
			return;
		}

		boolean crossing = swimUntilTick != Long.MIN_VALUE && tick < swimUntilTick && moveTarget != null;
		if (!crossing && pacing.shouldDecide(tick)) {
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
			if (tick % 20 == 0) {
				BlockPos at = sighting.entity().blockPosition();
				mind.relationships().with(sighting.agentId()).noteSeen(at.getX(), at.getY(), at.getZ(), tick);
			}
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
		if (shelterOrigin == null && mind.buildingSite().isPresent()) {
			// Picking up where it left off (say, after the server restarted).
			Home site = mind.buildingSite().get();
			shelterOrigin = homeOrigin(site);
			shelterDesign = site.design();
		}
		if (shelterOrigin == null) {
			// Not building anything yet: the next building would be the design it likes best.
			shelterDesign = mind.designToBuild().design();
		}
		followProject(mind, world, tick, log);
		if (shelterOrigin != null && mind.project().isEmpty()
				&& !PhysicalActions.stillBuildable(world, shelterOrigin, shelterDesign)
				&& !(home.isPresent() && homeOrigin(home.get()).equals(shelterOrigin))) {
			// Something's in the way now (or it's done): let that site go.
			shelterOrigin = null;
			shelterDesign = mind.designToBuild().design();
		}
		mind.noteBuilding(shelterOrigin != null);
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
		if (opportunities.log().isPresent() || opportunities.stone().isPresent()) {
			available.add(IntentType.GATHER_MATERIALS);
		}
		if (opportunities.shelterSite().isPresent()) {
			available.add(IntentType.BUILD_SHELTER);
		}
		Optional<java.util.Map.Entry<UUID, com.aicivilization.mind.RelationshipData>> friend = surroundings.nearbyAgents().isEmpty()
				&& mind.needs().social() < LONELY ? whereToFindSomeone(mind, tick) : Optional.empty();
		if (friend.isPresent()) {
			available.add(IntentType.SOCIALIZE);
		}
		if (home.isPresent() && !atHome && !isUnreachable(homeOrigin(home.get()), tick)) {
			available.add(IntentType.GO_HOME);
		}
		if (atHome) {
			failedTripsHome = 0;
			available.add(IntentType.REST);
		}
		// A wander toward a far point (searching, exploring) is kept across
		// decisions while the agent still wants the same thing, so it actually
		// gets somewhere instead of picking a new spot every few seconds.
		IntentType previousIntent = currentIntent;
		Vec3 previousWander = wandering ? moveTarget : null;
		long previousStart = taskStartTick;
		mind.noteThreat(surroundings.nearestHostile().isPresent());
		mind.noteMineable(opportunities.stone().isPresent());
		mind.noteStock(TradeBehavior.foodMeals(mind), opportunities.buildingBlocks());
		planRunner.offer(mind, available, tick, log);
		DecisionTrace trace = mind.decide(tick, available);
		currentIntent = trace.chosen();
		wandering = false;
		moveTarget = null;
		socialTarget = null;
		huntTarget = null;
		gatherTarget = null;
		planTarget = null;
		foodTask = null;
		foodTarget = null;
		breedPair = null;
		seekingFriend = null;
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
				} else if (!myFields.isEmpty() && nearestField().distSqr(entity.blockPosition()) <= 48 * 48
						&& tick - lastTendTick > TEND_INTERVAL_TICKS / 2) {
					// Nothing to hunt or pick here, but its own crops are coming on: see to them rather than roam.
					setFoodTask(FoodTask.TEND, nearestField().above());
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
			case GATHER_MATERIALS -> opportunities.stone().or(opportunities::log).ifPresentOrElse(pos -> {
				gatherTarget = pos;
				moveTarget = Vec3.atCenterOf(pos);
			}, () -> moveTarget = randomNearbyPoint(10));
			case BUILD_SHELTER -> opportunities.shelterSite().ifPresent(origin -> {
				mind.project().filter(p -> !p.siteKnown()).ifPresent(p -> {
					// It found the spot for the home it's building with its partner; they'll hear where when they meet.
					mind.setProject(p.at(origin.getX(), origin.getY(), origin.getZ()));
					mind.perceive(tick, "I picked a spot for the " + p.design().kind() + " I'm building with " + p.partnerName() + ".",
							0.5, Set.of(p.partner()));
				});
				shelterOrigin = origin;
				moveTarget = PhysicalActions.standingSpot(origin, shelterDesign);
			});
			case GO_HOME -> home.ifPresent(h -> moveTarget = PhysicalActions.bedSpot(homeOrigin(h), h.design()));
			case PURSUE_PLAN -> {
				PlanRunner.Next next = planRunner.begin(world, mind, tick, log, pos -> isUnreachable(pos, tick));
				if (next.pos() != null) {
					planTarget = next.pos();
					gatherTarget = next.pos();
					moveTarget = Vec3.atCenterOf(next.pos());
				} else if (next.dig() != null) {
					if (!digDown.start(mind, next.dig().block(), next.dig().item(), next.dig().count(), tick, log)) {
						planRunner.digFailed(next.dig().item());
					}
					pacing.onTaskFinished();
				} else if (next.search()) {
					moveTarget = randomNearbyPoint(24);
					wandering = true;
				} else {
					pacing.onTaskFinished();
				}
			}
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
					// Someone it hasn't just been talking to, if there is anyone; otherwise whoever's nearest.
					socialTarget = surroundings.nearbyAgents().stream()
							.filter(sighting -> !ConversationBehavior.recentlyTalked(mind.identity().id(), sighting.agentId(), tick))
							.findFirst().orElse(surroundings.nearbyAgents().get(0)).entity();
				} else if (surroundings.nearestPlayer().isPresent()) {
					socialTarget = surroundings.nearestPlayer().get();
				} else if (friend.isPresent()) {
					// Nobody in sight: go to where it last saw someone it likes.
					var data = friend.get().getValue();
					seekingFriend = friend.get().getKey();
					moveTarget = new Vec3(data.lastSeenX() + 0.5, data.lastSeenY(), data.lastSeenZ() + 0.5);
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
		if (gatherTarget != null && isUnreachable(gatherTarget, tick)
				|| foodTarget != null && isUnreachable(foodTarget, tick)) {
			// The obvious spot is one it couldn't get to earlier: look elsewhere instead.
			gatherTarget = null;
			foodTask = null;
			foodTarget = null;
			breedPair = null;
			moveTarget = randomNearbyPoint(16);
			wandering = true;
		}
		mind.setBuildingSite(shelterOrigin == null ? null
				: new Home(shelterOrigin.getX(), shelterOrigin.getY(), shelterOrigin.getZ(), shelterDesign, tick));
		if (unreachable.size() > 64) {
			unreachable.entrySet().removeIf(e -> e.getValue() <= tick);
		}
		// The right tool in hand for the job (it counts in a fight, and shows what the agent is up to).
		Crafting.hold(entity, mind, huntTarget != null ? Crafting.Tool.SWORD
				: currentIntent == IntentType.GATHER_MATERIALS && gatherTarget != null
						&& PhysicalActions.isMineTarget(world.getBlockState(gatherTarget)) ? Crafting.Tool.PICKAXE
				: currentIntent == IntentType.GATHER_MATERIALS ? Crafting.Tool.AXE
				: foodTask == FoodTask.TEND || foodTask == FoodTask.PLANT || foodTask == FoodTask.HARVEST ? Crafting.Tool.HOE
				: null);
		if (entity.isInWater() && tick >= swimUntilTick && huntTarget == null && socialTarget == null
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
					PhysicalActions.attack(entity, world, mind, huntTarget, tick, log);
					lastAttackTick = tick;
				}
			} else if (entity.getNavigation().isDone() || tick % 10 == 0) {
				entity.getNavigation().moveTo(huntTarget, CHASE_SPEED);
			}
			return;
		}

		if (moveTarget != null) {
			boolean working = currentIntent == IntentType.GATHER_MATERIALS || currentIntent == IntentType.BUILD_SHELTER
					|| currentIntent == IntentType.PURSUE_PLAN || foodTask != null;
			if (entity.position().distanceToSqr(moveTarget) <= (working ? ACT_DISTANCE_SQ : ARRIVE_DISTANCE_SQ)) {
				onArrivedAtLocation(mind, tick, world, log);
				moveTarget = null;
				pacing.onTaskFinished();
			} else if (entity.getNavigation().isDone() || headedElsewhere()) {
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

	/** Did something toward a goal: after a few times the goal is done, and says so. */
	private static void advanceGoal(AgentMind mind, IntentType intent, long tick, EventLog log) {
		mind.noteGoalProgress(intent, tick).ifPresent(done -> log.append(tick, EventType.MILESTONE,
				List.of(mind.identity().id()), mind.identity().name() + " did what they set out to: " + done.description() + ".",
				List.of()));
	}

	/** Seeds and the like beyond what anyone would carry are left behind (food is kept: it can be given away). */
	private static void leaveSurplus(AgentMind mind, long tick) {
		for (com.aicivilization.mind.Possession p : List.copyOf(mind.possessions())) {
			int extra = p.quantity() - PhysicalActions.carryLimit(p.itemId());
			if (extra > 0 && com.aicivilization.action.ItemKinds.nutrition(p.itemId()) == 0 && mind.takeItem(p.itemId(), extra)) {
				mind.perceive(tick, "I left a pile of " + extra + " " + com.aicivilization.action.ItemKinds.displayName(p.itemId())
						+ " behind; too much to carry.", 0.2, Set.of());
			}
		}
	}

	private void onArrivedAtLocation(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		advanceGoal(mind, currentIntent, tick, log);
		// Only getting somewhere new counts as having got unstuck (arriving where it already stood doesn't).
		if (lastStuckAt == null || entity.blockPosition().distSqr(lastStuckAt) > 64) {
			scrambleBackoff = 1;
		}
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
			case PURSUE_PLAN -> planRunner.arrive(world, mind, planTarget, tick, log);
			case GATHER_MATERIALS -> {
				if (gatherTarget != null) {
					if (PhysicalActions.isMineTarget(world.getBlockState(gatherTarget))) {
						PhysicalActions.mine(entity, world, mind, gatherTarget, tick, log);
					} else {
						PhysicalActions.chop(entity, world, mind, gatherTarget, tick, log);
					}
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
			case SOCIALIZE -> {
				if (seekingFriend != null) {
					// Got to where they were; if they were still about, perception will have picked them up.
					var data = mind.relationships().with(seekingFriend);
					if (tick - data.lastSeenTick() > 40) {
						data.forgetWhereSeen();
					}
					seekingFriend = null;
				}
			}
			case SEEK_SAFETY -> {
				mind.needs().adjustSafety(0.3);
				mind.perceive(tick, "I found a safer spot.", 0.35, Set.of());
			}
			case EXPLORE -> {
				// A walk that turned up nothing in particular: barely worth remembering, not worth telling.
				double importance = 0.15 + entity.getRandom().nextDouble() * 0.15;
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
		// Down a hole it can't jump out of (often one an agent dug): cut steps out now, no point scrambling about.
		if (caveEscape.inPit(world) && caveEscape.startClimb(mind, world, tick, log, 2)) {
			moveTarget = null;
			socialTarget = null;
			huntTarget = null;
			pacing.onTaskFinished();
			return true;
		}
		// A particular place it can't get to (ore inside a cave below, a field across water, a home up a cliff):
		// give up on that place for a while rather than scrambling about and trying it again.
		BlockPos goal = foodTarget != null ? foodTarget : gatherTarget != null ? gatherTarget
				: currentIntent == IntentType.GO_HOME ? mind.home().map(NeedsDrivenGoal::homeOrigin).orElse(null)
				: seekingFriend != null && moveTarget != null ? BlockPos.containing(moveTarget) : null;
		if (goal != null && !isUnreachable(goal, tick)) {
			if (currentIntent == IntentType.GO_HOME && foodTarget == null && gatherTarget == null
					&& ++failedTripsHome >= GIVE_UP_ON_HOME_AFTER) {
				giveUpOnHome(mind, tick, log);
			}
			unreachable.put(goal.immutable(), tick + UNREACHABLE_FOR_TICKS);
			moveTarget = null;
			gatherTarget = null;
			foodTask = null;
			foodTarget = null;
			entity.getNavigation().stop();
			pacing.onTaskFinished();
			return false;
		}
		// The place it got stuck goes on the list too, so it doesn't walk straight back into it.
		unreachable.put(entity.blockPosition().immutable(), tick + UNREACHABLE_FOR_TICKS);
		lastStuckAt = entity.blockPosition().immutable();
		// Stuck again and again: maybe it's cut off by water. Swim for it rather than starve on an island.
		if (scrambleBackoff >= SWIM_AFTER_BACKOFF && swimUntilTick < tick && startSwim(mind, world, tick, log)) {
			return true;
		}
		// No way across either: climb out up the hillside, the way a person would.
		if (scrambleBackoff >= SWIM_AFTER_BACKOFF && caveEscape.startClimb(mind, world, tick, log)) {
			moveTarget = null;
			socialTarget = null;
			huntTarget = null;
			pacing.onTaskFinished();
			return true;
		}
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

	/** The person it would most like to see, of those it remembers seeing in the last day or so. */
	private Optional<java.util.Map.Entry<UUID, com.aicivilization.mind.RelationshipData>> whereToFindSomeone(AgentMind mind, long tick) {
		return mind.relationships().asMap().entrySet().stream()
				.filter(e -> e.getValue().lastSeenTick() >= 0 && tick - e.getValue().lastSeenTick() < FRIEND_LEAD_TICKS)
				.filter(e -> !isUnreachable(new BlockPos(e.getValue().lastSeenX(), e.getValue().lastSeenY(), e.getValue().lastSeenZ()), tick))
				.max(java.util.Comparator.comparingDouble(e -> e.getValue().affinity() + e.getValue().trust()));
	}

	/**
	 * Lets it cross water for a while and heads for dry land some way off
	 * that it can only reach by swimming. Returns whether it found any.
	 */
	private boolean startSwim(AgentMind mind, ServerLevel world, long tick, EventLog log) {
		entity.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 8.0f);
		entity.getNavigation().setCanFloat(true);
		BlockPos here = entity.blockPosition();
		for (int distance : new int[] {16, 24, 32, 48, 64}) {
			for (int k = 0; k < 8; k++) {
				double angle = Math.PI * 2 * k / 8 + entity.getRandom().nextDouble() * 0.5;
				Optional<Vec3> shore = dryGroundAt(here.getX() + (int) (Math.cos(angle) * distance),
						here.getZ() + (int) (Math.sin(angle) * distance));
				// Land with open ground behind it, not another strip at the foot of a cliff.
				Optional<Vec3> inland = dryGroundAt(here.getX() + (int) (Math.cos(angle) * (distance + 10)),
						here.getZ() + (int) (Math.sin(angle) * (distance + 10)));
				if (shore.isEmpty() || inland.isEmpty() || Math.abs(inland.get().y - shore.get().y) > 4
						|| !waterBetween(world, here, BlockPos.containing(shore.get()))) {
					continue; // only land across the water is worth swimming for
				}
				var path = entity.getNavigation().createPath(BlockPos.containing(shore.get()), 1);
				// The planner only looks so far ahead: a swim that gets it onto dry land well away from here will do.
				BlockPos landing = path == null || path.getEndNode() == null ? null : path.getEndNode().asBlockPos();
				boolean goodLanding = landing != null && landing.distSqr(here) >= 256
						&& world.getFluidState(landing).isEmpty() && world.getFluidState(landing.below()).isEmpty()
						&& !isUnreachable(landing, tick) && waterBetween(world, here, landing);
				if (path != null && (path.canReach() || goodLanding)) {
					swimUntilTick = tick + SWIM_FOR_TICKS;
					currentIntent = IntentType.EXPLORE;
					moveTarget = shore.get();
					wandering = false;
					taskStartTick = tick + SWIM_FOR_TICKS - TASK_TIMEOUT_TICKS; // a long swim isn't a stalled task
					entity.getNavigation().moveTo(path, MOVE_SPEED);
					mind.perceive(tick, "I was cut off, so I swam for the far shore.", 0.5, Set.of());
					log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
							mind.identity().name() + " was cut off by water and swam for the far shore.", List.of());
					return true;
				}
			}
		}
		stopSwimming();
		return false;
	}

	/** Whether there's open water somewhere on the straight line between two places. */
	private static boolean waterBetween(ServerLevel world, BlockPos from, BlockPos to) {
		for (int i = 1; i < 8; i++) {
			double t = i / 8.0;
			int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
			int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
			int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			if (!world.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private void stopSwimming() {
		entity.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, -1.0f);
		entity.getNavigation().setCanFloat(false);
	}

	/** Whether this spot is at (or right by) somewhere it recently found it couldn't get to, or got stuck. */
	private boolean isUnreachable(BlockPos pos, long tick) {
		for (var entry : unreachable.entrySet()) {
			if (entry.getValue() > tick && entry.getKey().distSqr(pos) <= UNREACHABLE_RADIUS_SQ) {
				return true;
			}
		}
		return false;
	}

	/** Within a few blocks of its own home, where narrow spaces are rooms, not holes. */
	private boolean nearHome(AgentMind mind) {
		return mind.home().isPresent() && entity.blockPosition().distSqr(homeOrigin(mind.home().get())) <= 25;
	}

	private static BlockPos homeOrigin(Home home) {
		return new BlockPos(home.x(), home.y(), home.z());
	}

	/** A building just got its last block: the agent's first one becomes its home. */
	/**
	 * Keeps a shared building on track: builds at the agreed site with the
	 * agreed design once it knows where that is, moves in once it's finished
	 * (whoever placed the last block), and gives up if it never comes together.
	 */
	private void followProject(AgentMind mind, ServerLevel world, long tick, EventLog log) {
		Optional<com.aicivilization.mind.Project> current = mind.project();
		if (current.isEmpty()) {
			return;
		}
		com.aicivilization.mind.Project project = current.get();
		if (mind.home().isPresent()) {
			mind.setProject(null);
			return;
		}
		if (tick - project.agreedTick() > (project.siteKnown() ? PROJECT_PATIENCE_TICKS * 2 : PROJECT_PATIENCE_TICKS)) {
			mind.setProject(null);
			mind.perceive(tick, "The home " + project.partnerName() + " and I meant to build never came together; I'll manage on my own.",
					0.5, Set.of(project.partner()));
			log.append(tick, EventType.ACTION, List.of(mind.identity().id(), project.partner()),
					mind.identity().name() + " gave up on building a home with " + project.partnerName() + ".", List.of());
			if (shelterOrigin != null && shelterOrigin.equals(new BlockPos(project.x(), project.y(), project.z()))) {
				shelterOrigin = null;
			}
			return;
		}
		if (!project.siteKnown()) {
			if (shelterOrigin == null) {
				shelterDesign = project.design();
			}
			return;
		}
		BlockPos site = new BlockPos(project.x(), project.y(), project.z());
		if (PhysicalActions.remainingCells(world, site, project.design()).isEmpty()) {
			moveIntoSharedHome(mind, project, site, tick, log, false);
			if (site.equals(shelterOrigin)) {
				shelterOrigin = null;
			}
			return;
		}
		shelterOrigin = site;
		shelterDesign = project.design();
	}

	private void moveIntoSharedHome(AgentMind mind, com.aicivilization.mind.Project project, BlockPos site, long tick,
			EventLog log, boolean placedLastBlock) {
		mind.setProject(null);
		mind.setHome(new Home(site.getX(), site.getY(), site.getZ(), project.design(), tick));
		mind.needs().adjustBelonging(0.3);
		mind.relationships().with(project.partner()).recordConversation(tick, 0.15, 0.15);
		mind.perceive(tick, (placedLastBlock ? "I put the last block on " : "We finished ") + "the " + project.design().kind()
				+ " " + project.partnerName() + " and I built together. It's our home now.", 0.9, Set.of(project.partner()));
		log.append(tick, EventType.MILESTONE, List.of(mind.identity().id(), project.partner()),
				mind.identity().name() + " moved into the " + project.design().kind() + " they built with "
						+ project.partnerName() + ".", List.of());
	}

	private void onBuildingFinished(AgentMind mind, long tick, EventLog log) {
		BlockPos origin = shelterOrigin;
		Design design = shelterDesign;
		shelterOrigin = null;
		mind.setBuildingSite(null);
		Optional<com.aicivilization.mind.Project> project = mind.project();
		if (project.isPresent() && project.get().siteKnown()
				&& new BlockPos(project.get().x(), project.get().y(), project.get().z()).equals(origin)) {
			moveIntoSharedHome(mind, project.get(), origin, tick, log, true);
			return;
		}
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
		failedTripsHome = 0;
	}

	/** It can't find a way back any more (a cliff, a river, a cave it fell into): time to settle where it is. */
	private void giveUpOnHome(AgentMind mind, long tick, EventLog log) {
		failedTripsHome = 0;
		Home home = mind.home().orElse(null);
		if (home == null) {
			return;
		}
		mind.loseHome();
		String kind = home.design().kind();
		mind.perceive(tick, "I couldn't find my way back to my " + kind + ", time after time. I'll have to make a new home.",
				0.85, Set.of());
		log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
				mind.identity().name() + " gave up trying to get back to their " + kind + " and will settle somewhere new.",
				List.of());
	}

	private void onArrivedAtSocialTarget(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		if (socialTarget instanceof AgentEntity otherAgent) {
			double roll = entity.getRandom().nextDouble();
			ConversationBehavior.attempt(entity, otherAgent, tick, log, roll);
			advanceGoal(mind, IntentType.SOCIALIZE, tick, log);
		} else if (socialTarget instanceof Player player) {
			mind.needs().adjustSocial(0.1);
			mind.perceive(tick, "I met a person named " + player.getName().getString() + ".", 0.4,
					Set.of(player.getUUID()));
		}
	}

	/** A random spot some way off, on dry ground where possible (not mid-air, inside a hill or out on a lake). */
	/**
	 * A spot some way off on dry ground worth heading for: preferably one it
	 * can actually get all the way to, never one whose route runs through a
	 * cave, and otherwise the one it gets nearest to. (Aiming at spots beyond a
	 * cliff only ever led agents into dead ends at the foot of it.)
	 */
	private Vec3 randomNearbyPoint(double radius) {
		Vec3 best = null;
		double bestLeft = Double.MAX_VALUE;
		ServerLevel world = entity.level() instanceof ServerLevel w ? w : null;
		for (int attempt = 0; attempt < 4; attempt++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2;
			double distance = radius * 0.5 + entity.getRandom().nextDouble() * radius * 0.5;
			Vec3 flat = entity.position().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
			Optional<Vec3> dry = dryGroundAt((int) Math.floor(flat.x), (int) Math.floor(flat.z));
			if (dry.isEmpty() || world == null || isUnreachable(BlockPos.containing(dry.get()), world.getGameTime())) {
				continue;
			}
			var path = entity.getNavigation().createPath(BlockPos.containing(dry.get()), 1);
			if (path == null || path.getEndNode() == null || CaveEscape.goesUnderground(world, path)) {
				continue;
			}
			if (path.canReach()) {
				// Already planned: walk it rather than planning the same route again.
				entity.getNavigation().moveTo(path, MOVE_SPEED);
				return dry.get();
			}
			// How much of the way it would still have left, as a share of the whole way.
			double left = Math.sqrt(path.getEndNode().asBlockPos().distSqr(BlockPos.containing(dry.get())))
					/ Math.max(1.0, distance);
			if (left < bestLeft) {
				bestLeft = left;
				best = dry.get();
			}
		}
		if (best != null) {
			return best;
		}
		// Nowhere good that far: a short step to dry ground nearby, rather than a blind heading (often into a cave).
		BlockPos here = entity.blockPosition();
		for (int attempt = 0; attempt < 6; attempt++) {
			Optional<Vec3> near = dryGroundAt(here.getX() + entity.getRandom().nextInt(13) - 6,
					here.getZ() + entity.getRandom().nextInt(13) - 6);
			if (near.isPresent() && Math.abs(near.get().y - entity.getY()) <= 3) {
				return near.get();
			}
		}
		return entity.position();
	}

	/** Whether the route being walked leads somewhere other than where it now wants to go. */
	private boolean headedElsewhere() {
		var path = entity.getNavigation().getPath();
		return path != null && moveTarget != null && path.getTarget().distSqr(BlockPos.containing(moveTarget)) > 9;
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
			case PURSUE_PLAN -> "work on what it set out to make";
		};
	}
}





