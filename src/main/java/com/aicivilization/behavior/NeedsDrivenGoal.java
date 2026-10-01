package com.aicivilization.behavior;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The "fast system": a plain utility AI, no LLM involved. Every tick it
 * re-perceives the world, occasionally (per {@link com.aicivilization.population.AgentScheduler})
 * asks the agent's mind to score the intents currently physically available,
 * and drives whatever it chose. This is the only {@code Goal} the agent
 * needs — it subsumes moving, foraging, fleeing, socializing, and idling.
 */
public final class NeedsDrivenGoal extends Goal {

	private static final PerceptionSystem PERCEPTION = new PerceptionSystem();
	private static final double MOVE_SPEED = 0.6;
	private static final double ARRIVE_DISTANCE_SQ = 4.0;
	private static final long DECISION_INTERVAL_TICKS = 60;

	private final AgentEntity entity;
	private final Set<UUID> knownAgentIds = new HashSet<>();

	private IntentType currentIntent = IntentType.IDLE;
	private Vec3 moveTarget;
	private Entity socialTarget;
	private long nextDecisionTick = 0;
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

		boolean idle = moveTarget == null && socialTarget == null;
		if (idle || tick >= nextDecisionTick) {
			decideAndAct(mind, surroundings, tick, world, log);
			nextDecisionTick = tick + DECISION_INTERVAL_TICKS;
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
		DecisionTrace trace = mind.decide(tick, surroundings.availableIntents());
		currentIntent = trace.chosen();
		moveTarget = null;
		socialTarget = null;

		log.append(tick, EventType.DECISION, List.of(mind.identity().id()),
				mind.identity().name() + " decided to " + describeIntent(currentIntent) + ".",
				trace.causes());

		switch (currentIntent) {
			case FORAGE_FOOD -> surroundings.nearestAnimal().ifPresentOrElse(
					animal -> moveTarget = animal.position(),
					() -> moveTarget = randomNearbyPoint(18));
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
		if (moveTarget != null) {
			if (entity.position().distanceToSqr(moveTarget) <= ARRIVE_DISTANCE_SQ) {
				onArrivedAtLocation(mind, tick);
				moveTarget = null;
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

	private void onArrivedAtLocation(AgentMind mind, long tick) {
		switch (currentIntent) {
			case FORAGE_FOOD -> {
				mind.needs().adjustFood(0.35);
				mind.perceive(tick, "I found food nearby and ate.", 0.35, Set.of());
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
		};
	}
}
