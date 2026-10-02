package com.aicivilization.behavior;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.DecisionTrace;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
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
	/** Soonest a finished task can trigger the next decision. */
	private static final long MIN_DECISION_GAP_TICKS = 20;

	private final AgentEntity entity;
	private final Set<UUID> knownAgentIds = new HashSet<>();

	private IntentType currentIntent = IntentType.IDLE;
	private Vec3 moveTarget;
	private Entity socialTarget;
	private Animal huntTarget;
	private BlockPos gatherTarget;
	/** The shelter this agent is part-way through building, if any. */
	private BlockPos shelterOrigin;
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
		if (!AICivilizationMod.isSimulationEnabled()) {
			// Switched off with /civ off: stand still, and let needs and decisions wait.
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
		if (tick % EAT_CHECK_INTERVAL_TICKS == 0) {
			PhysicalActions.tryEat(entity, mind, tick, log);
		}

		if (pacing.shouldDecide(tick)) {
			decideAndAct(mind, surroundings, tick, world, log);
			pacing.onDecided(tick);
		}

		pursueCurrentTarget(mind, tick, world, log);
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
		PhysicalActions.Opportunities opportunities = PhysicalActions.scan(entity, world, mind, shelterOrigin);
		Set<IntentType> available = EnumSet.copyOf(surroundings.availableIntents());
		if (opportunities.log().isPresent()) {
			available.add(IntentType.GATHER_MATERIALS);
		}
		if (opportunities.shelterSite().isPresent()) {
			available.add(IntentType.BUILD_SHELTER);
		}
		DecisionTrace trace = mind.decide(tick, available);
		currentIntent = trace.chosen();
		moveTarget = null;
		socialTarget = null;
		huntTarget = null;
		gatherTarget = null;
		taskStartTick = tick;

		if (pacing.shouldLog(currentIntent)) {
			log.append(tick, EventType.DECISION, List.of(mind.identity().id()),
					mind.identity().name() + " decided to " + describeIntent(currentIntent) + ".",
					trace.causes());
		}

		switch (currentIntent) {
			case FORAGE_FOOD -> surroundings.nearestAnimal().ifPresentOrElse(
					animal -> huntTarget = animal,
					() -> moveTarget = randomNearbyPoint(18));
			case GATHER_MATERIALS -> opportunities.log().ifPresentOrElse(pos -> {
				gatherTarget = pos;
				moveTarget = Vec3.atCenterOf(pos);
			}, () -> moveTarget = randomNearbyPoint(10));
			case BUILD_SHELTER -> opportunities.shelterSite().ifPresent(origin -> {
				shelterOrigin = origin;
				moveTarget = PhysicalActions.standingSpot(origin);
			});
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
			case EXPLORE -> moveTarget = randomNearbyPoint(24);
			case REST, IDLE -> {
				// stay put; small passive regen happens in pursueCurrentTarget.
			}
		}
	}

	private void pursueCurrentTarget(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		boolean busy = moveTarget != null || socialTarget != null || huntTarget != null;
		if (busy && tick - taskStartTick > TASK_TIMEOUT_TICKS) {
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
			boolean working = currentIntent == IntentType.GATHER_MATERIALS || currentIntent == IntentType.BUILD_SHELTER;
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
			needs.adjustSafety(0.001);
			needs.adjustBelonging(0.0006);
		}
	}

	private void onArrivedAtLocation(AgentMind mind, long tick, ServerLevel world, EventLog log) {
		switch (currentIntent) {
			case GATHER_MATERIALS -> {
				if (gatherTarget != null) {
					PhysicalActions.chop(entity, world, mind, gatherTarget, tick, log);
					gatherTarget = null;
				}
			}
			case BUILD_SHELTER -> {
				if (shelterOrigin != null) {
					PhysicalActions.build(entity, world, mind, shelterOrigin, 4, tick, log);
					if (PhysicalActions.remainingCells(world, shelterOrigin).isEmpty()) {
						shelterOrigin = null;
					}
				}
			}
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

	private Vec3 randomNearbyPoint(double radius) {
		double angle = entity.getRandom().nextDouble() * Math.PI * 2;
		double distance = radius * 0.5 + entity.getRandom().nextDouble() * radius * 0.5;
		return entity.position().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
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
		};
	}
}
