package com.aicivilization.behavior;

import com.aicivilization.action.Crafting;
import com.aicivilization.action.ItemKinds;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A trip down for ore it can't find lying open: cutting a staircase into
 * the ground, looking about for the ore as it goes and mining what it finds.
 * It stops when it has enough, when it gets hungry or night comes, or when
 * it has gone deep enough; the cave escape then brings it back up as the end
 * of a trip, not as being lost.
 */
final class DigDown {

	/** Steps down before it gives up for this trip. */
	private static final int MAX_STEPS = 48;
	/** Blocks around the tunnel it can see and reach into for ore. */
	private static final int LOOK_RADIUS = 3;
	/** Never below this height: lava lakes are common further down. */
	private static final int LOWEST_Y = 0;
	private static final double HUNGRY = 0.3;

	private final AgentEntity entity;
	private Block target;
	/** The same ore as it occurs deep down (deepslate iron ore), if there is one. */
	private Block deepTarget;
	private String targetName = "";
	private String wantedItem = "";
	private int wanted;
	private int steps;
	private Direction direction = Direction.NORTH;
	private long nextTick;
	private boolean active;
	private boolean lastFound;

	DigDown(AgentEntity entity) {
		this.entity = entity;
	}

	boolean active() {
		return active;
	}

	/** Sets off down for {@code count} of {@code item}, found in {@code block}. Returns whether it could start. */
	boolean start(AgentMind mind, String block, String item, int count, long tick, EventLog log) {
		Identifier key = Identifier.tryParse(block);
		Optional<Block> b = key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
		if (b.isEmpty() || Crafting.best(mind, Crafting.Tool.PICKAXE).isEmpty()) {
			return false;
		}
		target = b.get();
		deepTarget = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(block.replace("minecraft:", "minecraft:deepslate_")))
				.orElse(target);
		targetName = ItemKinds.displayName(block);
		wantedItem = item;
		wanted = mind.countOf(item) + count;
		steps = 0;
		direction = Direction.Plane.HORIZONTAL.getRandomDirection(entity.getRandom());
		nextTick = tick;
		active = true;
		Crafting.hold(entity, mind, Crafting.Tool.PICKAXE);
		mind.perceive(tick, "I started digging down to look for " + targetName + ".", 0.45, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " started digging down to look for " + targetName + ".", List.of());
		return true;
	}

	/** Called every tick while active. Returns false once the trip is over. */
	boolean tick(ServerLevel world, AgentMind mind, long tick, EventLog log, boolean night) {
		if (!active) {
			return false;
		}
		if (tick < nextTick) {
			return true;
		}
		boolean enough = mind.countOf(wantedItem) >= wanted;
		if (enough || mind.needs().food() < HUNGRY || night || steps >= MAX_STEPS
				|| Crafting.best(mind, Crafting.Tool.PICKAXE).isEmpty()) {
			stop(mind, tick, log, enough);
			return false;
		}
		BlockPos feet = entity.blockPosition();
		// Ore in sight of the tunnel: reach in and take it.
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-LOOK_RADIUS, -1, -LOOK_RADIUS),
				feet.offset(LOOK_RADIUS, 2, LOOK_RADIUS))) {
			BlockState state = world.getBlockState(pos);
			if ((state.is(target) || state.is(deepTarget)) && PhysicalActions.mine(entity, world, mind, pos.immutable(), tick, log)) {
				nextTick = tick + 10;
				return true;
			}
		}
		if (feet.getY() <= LOWEST_Y || !stepDown(world, mind, tick, log)) {
			stop(mind, tick, log, false);
			return false;
		}
		steps++;
		nextTick = tick + 12;
		return true;
	}

	private boolean stepDown(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		BlockPos feet = entity.blockPosition();
		for (int turn = 0; turn < 4; turn++) {
			Direction dir = turn == 0 ? direction : turn == 1 ? direction.getClockWise()
					: turn == 2 ? direction.getCounterClockWise() : direction.getOpposite();
			BlockPos step = feet.relative(dir).below();
			List<BlockPos> clear = List.of(step, step.above(), step.above(2));
			BlockPos floor = step.below();
			if (!world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP)
					|| !clear.stream().allMatch(p -> CaveEscape.diggable(world.getBlockState(p))
							&& !CaveEscape.nearLiquid(world, p))
					|| world.getBlockState(step.above(3)).getBlock() instanceof FallingBlock) {
				continue; // a drop, water or lava, or sand that would fall in
			}
			Optional<String> pick = Crafting.best(mind, Crafting.Tool.PICKAXE);
			for (BlockPos pos : clear) {
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || state.canBeReplaced()) {
					continue;
				}
				entity.swing(InteractionHand.MAIN_HAND);
				world.destroyBlock(pos, true, entity);
				PhysicalActions.collectFreshDrops(world, mind, net.minecraft.world.phys.Vec3.atCenterOf(pos), tick);
				pick.ifPresent(p -> Crafting.wear(entity, mind, p, tick, log));
			}
			direction = dir;
			entity.getNavigation().stop();
			entity.teleportTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
			entity.resetFallDistance();
			return true;
		}
		return false;
	}

	/** Whether the trip that just ended found what it went for. */
	boolean lastFound() {
		return lastFound;
	}

	String wantedItem() {
		return wantedItem;
	}

	private void stop(AgentMind mind, long tick, EventLog log, boolean enough) {
		active = false;
		lastFound = enough;
		String how = enough ? "found the " + targetName + " I went down for" : "found no " + targetName;
		mind.perceive(tick, "I dug down " + steps + " steps and " + how + "; time to head back up.", enough ? 0.55 : 0.4,
				Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()), mind.identity().name() + " dug down " + steps
				+ " steps and " + (enough ? "found the " + targetName + " they wanted." : "found no " + targetName + "."),
				List.of());
	}
}
