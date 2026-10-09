package com.aicivilization.behavior;

import com.aicivilization.action.ItemKinds;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.action.Verbs;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.Planner;
import com.aicivilization.mind.Possession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Carries out a goal that names a thing to have, one step at a time. The
 * plan is worked out afresh from what the agent carries each time it picks
 * the goal up again, so being interrupted (to eat, sleep, flee) loses
 * nothing: progress is in its pack and in the world (a furnace still
 * cooking). Every plan, step, gap and outcome is an event.
 */
final class PlanRunner {

	/** Failed tries at one step before the goal is given up. */
	private static final int GIVE_UP_AFTER = 5;
	private static final int LOG_SEARCH_RADIUS = 16;
	private static final int ORE_SEARCH_RADIUS = 12;

	/** What to do next for the plan: walk to {@code pos} and act there, or wander and look, or nothing more now. */
	record Next(BlockPos pos, boolean search, Planner.Step dig) {
		Next(BlockPos pos, boolean search) {
			this(pos, search, null);
		}

		static Next done() {
			return new Next(null, false);
		}
	}

	private final AgentEntity entity;
	private long announcedGoal = -1;
	private long gapNotedGoal = -1;
	private Planner.Step current;
	private final Map<String, Integer> failures = new HashMap<>();

	PlanRunner(AgentEntity entity) {
		this.entity = entity;
	}

	/** The goal being worked on, for "decided to work on ...". */
	String goalDescription() {
		AgentMind mind = entity.mind();
		return mind == null ? "" : mind.targetGoal().map(Goal::description).orElse("");
	}

	/**
	 * Before a decision: is there a plan worth offering? Finishes a goal that's
	 * met, notes a gap once, and gives up on one that keeps failing.
	 */
	void offer(AgentMind mind, Set<IntentType> available, long tick, EventLog log) {
		Optional<Goal> goal = mind.targetGoal();
		if (goal.isEmpty()) {
			return;
		}
		Goal g = goal.get();
		String name = ItemKinds.displayName(g.targetItem());
		if (mind.countOf(g.targetItem()) >= g.targetCount()) {
			mind.finishGoal(g.id());
			failures.clear();
			mind.perceive(tick, "I made the " + name + " I set out to make.", 0.8, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " made the " + name + " they set out to make.", List.of());
			return;
		}
		Planner.Result plan = plan(mind, g);
		if (!plan.ok()) {
			if (gapNotedGoal != g.id()) {
				gapNotedGoal = g.id();
				String missing = ItemKinds.displayName(plan.gap().get());
				mind.perceive(tick, "I want to make " + name + ", but I don't know how to get " + missing + ".", 0.55, Set.of());
				log.append(tick, EventType.DECISION, List.of(mind.identity().id()),
						mind.identity().name() + " wants to make " + name + " but doesn't know how to get " + missing + ".",
						List.of());
			}
			return;
		}
		Planner.Step first = plan.steps().get(0);
		if (failures.getOrDefault(first.item(), 0) >= GIVE_UP_AFTER) {
			mind.finishGoal(g.id());
			failures.clear();
			String step = first.describe();
			mind.perceive(tick, "I gave up on making " + name + "; I couldn't " + step + ".", 0.6, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " gave up on making " + name + ": they couldn't " + step + ".", List.of());
			return;
		}
		available.add(IntentType.PURSUE_PLAN);
	}

	/** The plan was chosen: start on its first step. Craft steps happen on the spot. */
	Next begin(ServerLevel world, AgentMind mind, long tick, EventLog log, Predicate<BlockPos> unreachable) {
		Optional<Goal> goal = mind.targetGoal();
		if (goal.isEmpty()) {
			return Next.done();
		}
		Planner.Result plan = plan(mind, goal.get());
		if (!plan.ok() || plan.steps().isEmpty()) {
			return Next.done();
		}
		announce(mind, goal.get(), plan, tick, log);
		current = plan.steps().get(0);
		BlockPos here = entity.blockPosition();
		switch (current.kind()) {
			case CRAFT -> {
				if (!Verbs.craft(entity, world, mind, current, tick, log)) {
					failed(current);
				}
				return Next.done();
			}
			case SMELT -> {
				Optional<BlockPos> furnace = Verbs.findNear(world, here, Blocks.FURNACE, 24);
				if (furnace.isEmpty()) {
					furnace = Verbs.place(entity, world, mind, Planner.FURNACE, tick, log);
				}
				if (furnace.isEmpty()) {
					failed(current);
					return Next.done();
				}
				return new Next(furnace.get(), false);
			}
			case GATHER -> {
				boolean log_ = ItemKinds.isLog(current.item());
				Optional<BlockPos> spot = log_
						? Verbs.findExposed(world, here, current.block(), LOG_SEARCH_RADIUS, 6, 12, unreachable)
						: current.block().endsWith("_ore")
								? Verbs.findExposed(world, here, current.block(), ORE_SEARCH_RADIUS, ORE_SEARCH_RADIUS, 4, unreachable)
								// Plain stone is everywhere: take some near the surface rather than deep in a hill.
								: Verbs.findExposed(world, here, current.block(), ORE_SEARCH_RADIUS, 3, 3, unreachable);
				if (spot.isPresent()) {
					return new Next(spot.get(), false);
				}
				if (!log_) {
					// Ore (and, under soil, plain stone) isn't lying about on the surface: dig down for it.
					return new Next(null, false, current);
				}
				// None in sight: go and look somewhere else.
				failed(current);
				return new Next(null, true);
			}
		}
		return Next.done();
	}

	/** Got to where the step happens. */
	void arrive(ServerLevel world, AgentMind mind, BlockPos pos, long tick, EventLog log) {
		if (current == null || pos == null) {
			return;
		}
		switch (current.kind()) {
			case GATHER -> {
				boolean got = ItemKinds.isLog(current.item())
						? PhysicalActions.chop(entity, world, mind, pos, tick, log)
						: PhysicalActions.mine(entity, world, mind, pos, tick, log);
				if (got) {
					failures.remove(current.item());
				} else {
					failed(current);
				}
			}
			case SMELT -> {
				Verbs.SmeltResult result = Verbs.smelt(entity, world, mind, pos, current, tick, log);
				if (result == Verbs.SmeltResult.BUSY || result == Verbs.SmeltResult.FAILED) {
					// Someone else's furnace, or one it can't use: set up its own if it has one, and use that.
					Optional<BlockPos> own = Verbs.place(entity, world, mind, Planner.FURNACE, tick, log);
					if (own.isPresent()) {
						result = Verbs.smelt(entity, world, mind, own.get(), current, tick, log);
					}
				}
				if (result == Verbs.SmeltResult.COLLECTED || result == Verbs.SmeltResult.LOADED) {
					failures.remove(current.item());
				} else if (result != Verbs.SmeltResult.WAITING) {
					failed(current);
				}
			}
			case CRAFT -> {
			}
		}
		current = null;
	}

	/** A dig for {@code item} came back empty-handed. */
	void digFailed(String item) {
		failures.merge(item, 1, Integer::sum);
	}

	private void failed(Planner.Step step) {
		failures.merge(step.item(), 1, Integer::sum);
	}

	private void announce(AgentMind mind, Goal goal, Planner.Result plan, long tick, EventLog log) {
		if (announcedGoal == goal.id()) {
			return;
		}
		announcedGoal = goal.id();
		List<String> steps = plan.steps().stream().map(Planner.Step::describe).toList();
		String name = ItemKinds.displayName(goal.targetItem());
		mind.perceive(tick, "I worked out how to make " + name + ": " + String.join(", then ", steps) + ".", 0.5, Set.of());
		log.append(tick, EventType.DECISION, List.of(mind.identity().id()),
				mind.identity().name() + " set out to make " + name + " in " + steps.size() + " steps.", List.of(), steps);
	}

	private static Planner.Result plan(AgentMind mind, Goal goal) {
		Map<String, Integer> carrying = new HashMap<>();
		for (Possession p : mind.possessions()) {
			carrying.merge(p.itemId(), p.quantity(), Integer::sum);
		}
		return Planner.plan(mind.recipeBook(), carrying, goal.targetItem(), goal.targetCount());
	}
}
