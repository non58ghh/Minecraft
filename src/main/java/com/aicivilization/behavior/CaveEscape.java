package com.aicivilization.behavior;

import com.aicivilization.action.Crafting;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Home;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Getting out of caves. An agent that finds itself underground (the
 * surface well above its head, no sky) for a while first looks for a way to
 * walk out to open ground; if there is none, or it isn't getting anywhere,
 * it digs a staircase up toward the daylight, the way a player would. With
 * a pickaxe the digging goes much faster through stone and it keeps the
 * cobblestone; without one it still gets out, just slowly. It only digs
 * natural ground (stone, dirt, sand, gravel, ores), never anything built,
 * and turns away from water and lava.
 */
final class CaveEscape {

	/** How long underground before it counts as lost down there. */
	private static final int LOST_AFTER_TICKS = 300;
	/** Re-plan the walk out this often, or start digging if the walk isn't working. */
	private static final int WALK_PATIENCE_TICKS = 300;
	private static final int MAX_DIG_STEPS = 96;
	/** Surface this far above the feet (and no sky) means underground. */
	private static final int UNDERGROUND_DEPTH = 5;
	private static final double SPEED = 0.6;
	/** Back under a cave mouth it only just got out of: don't wait as long. */
	private static final int LOST_AGAIN_AFTER_TICKS = 60;
	private static final long KNOWN_CAVE_TICKS = 6000;
	/** Only a dig of this many steps or more makes the timeline. */
	private static final int ANNOUNCE_AFTER_STEPS = 3;

	private enum Mode { NONE, WALK, DIG }

	private final AgentEntity entity;
	private int undergroundTicks;
	private Mode mode = Mode.NONE;
	private long modeStartTick;
	private Vec3 walkAnchor;
	private Direction digDirection;
	private int digSteps;
	private long nextDigTick;
	/** While climbing out of somewhere hemmed in: the height to reach. */
	private int climbToY = Integer.MIN_VALUE;
	private static final int CLIMB_HEIGHT = 8;
	private long lastClimbNews = Long.MIN_VALUE / 2;
	private static final long CLIMB_NEWS_EVERY = 12000;
	/** Whether this episode made the timeline (only once digging started), so its end does too. */
	private boolean announced;
	private BlockPos lastEscapeSpot;
	private long lastEscapeTick;
	/** Went down on purpose (to mine) and hasn't come back up yet. */
	private boolean onTrip;

	CaveEscape(AgentEntity entity) {
		this.entity = entity;
	}

	/** Whether a spot is inside the earth: well below the surface with rock or soil overhead. */
	static boolean isEnclosed(ServerLevel world, BlockPos feet) {
		int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
		if (surface - feet.getY() < UNDERGROUND_DEPTH || world.canSeeSky(feet.above())) {
			return false;
		}
		int earth = 0;
		for (int y = feet.getY() + 2; y < surface && earth < 3; y++) {
			BlockState state = world.getBlockState(new BlockPos(feet.getX(), y, feet.getZ()));
			if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND)
					|| state.is(Blocks.GRAVEL)) {
				earth++;
			}
		}
		return earth >= 3;
	}

	/** Whether walking this path would take it through a cave. */
	static boolean goesUnderground(ServerLevel world, Path path) {
		for (int i = 0; i < path.getNodeCount(); i += 2) {
			if (isEnclosed(world, path.getNodePos(i))) {
				return true;
			}
		}
		return false;
	}

	boolean isUnderground(ServerLevel world, Optional<Home> home) {
		BlockPos feet = entity.blockPosition();
		if (home.isPresent() && feet.distSqr(new BlockPos(home.get().x(), home.get().y(), home.get().z())) <= 25) {
			return false; // under one's own roof
		}
		// Rock or earth overhead, not a roof someone built or a tree: that's a cave.
		return isEnclosed(world, feet);
	}

	/**
	 * Called every tick. Returns true while it is busy getting the agent out
	 * (the normal decide-and-act loop should wait).
	 */
	/**
	 * Hemmed in above ground (a shore strip under steep hills, a pit): cut a
	 * staircase up the slope until it's a good way higher. Returns whether
	 * there was a slope worth climbing.
	 */
	boolean startClimb(AgentMind mind, ServerLevel world, long tick, EventLog log) {
		return startClimb(mind, world, tick, log, 3);
	}

	/** Whether it's down a hole: walls at least two blocks high on every side. */
	boolean inPit(ServerLevel world) {
		BlockPos feet = entity.blockPosition();
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos side = feet.relative(dir);
			if (world.getBlockState(side.above()).getCollisionShape(world, side.above()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	boolean startClimb(AgentMind mind, ServerLevel world, long tick, EventLog log, int minRise) {
		BlockPos feet = entity.blockPosition();
		Direction best = null;
		int bestRise = minRise - 1;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos probe = feet.relative(dir, 8);
			int rise = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ()) - feet.getY();
			BlockState top = world.getBlockState(probe.atY(feet.getY() + rise - 1));
			// A real slope of earth or rock, not a tree trunk standing in the way.
			boolean hillside = top.is(BlockTags.SUBSTRATE_OVERWORLD) || top.is(BlockTags.BASE_STONE_OVERWORLD) || top.is(BlockTags.SAND)
					|| top.is(Blocks.GRAVEL) || top.is(Blocks.SNOW_BLOCK);
			if (rise > bestRise && rise < 40 && hillside) {
				best = dir;
				bestRise = rise;
			}
		}
		if (best == null) {
			return false;
		}
		mode = Mode.DIG;
		climbToY = feet.getY() + Math.min(bestRise, CLIMB_HEIGHT);
		digDirection = best;
		digSteps = 0;
		nextDigTick = tick;
		entity.getNavigation().stop();
		Crafting.hold(entity, mind, Crafting.Tool.PICKAXE);
		if (tick - lastClimbNews > CLIMB_NEWS_EVERY) {
			lastClimbNews = tick;
			mind.perceive(tick, "I was hemmed in, so I cut steps up the hillside.", 0.4, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " was hemmed in and cut steps up the hillside.", List.of());
		}
		return true;
	}

	boolean isClimbing() {
		return climbToY != Integer.MIN_VALUE;
	}

	boolean tick(AgentMind mind, ServerLevel world, long tick, EventLog log, boolean purposeful) {
		if (isClimbing()) {
			if (entity.blockPosition().getY() >= climbToY || digSteps > CLIMB_HEIGHT + 8) {
				climbToY = Integer.MIN_VALUE;
				mode = Mode.NONE;
				Crafting.hold(entity, mind, null);
				return false;
			}
			if (tick >= nextDigTick) {
				digStep(world, mind, tick, log);
				if (mode == Mode.NONE) {
					climbToY = Integer.MIN_VALUE; // boxed in: give up the climb
				}
			}
			return true;
		}
		boolean underground;
		if (tick % 10 != 0) {
			underground = undergroundTicks > 0 || mode != Mode.NONE;
		} else if (mode == Mode.NONE) {
			underground = isUnderground(world, mind.home());
		} else {
			// Once escaping, it isn't out until it stands on the surface itself: stopping a few blocks short
			// leaves it in a shaft it would only wander back down.
			BlockPos feet = entity.blockPosition();
			underground = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - feet.getY() > 1;
		}
		if (!underground) {
			if (announced) {
				mind.perceive(tick, "I dug my way back up to daylight.", 0.6, Set.of());
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + " dug their way back up to daylight.", List.of());
				Crafting.hold(entity, mind, null);
			}
			if (mode != Mode.NONE) {
				// Remember the cave mouth: if it wanders back under here soon, it gets out again sooner.
				lastEscapeSpot = entity.blockPosition();
				lastEscapeTick = tick;
			}
			announced = false;
			onTrip = false;
			undergroundTicks = 0;
			mode = Mode.NONE;
			return false;
		}
		if (mode == Mode.NONE) {
			if (purposeful) {
				// Down here on purpose (mining what it can see): not lost.
				if (!onTrip) {
					onTrip = true;
					// Its own business: the mining itself is what shows up on the timeline.
					mind.perceive(tick, "I went underground to mine.", 0.2, Set.of());
				}
				undergroundTicks = 0;
				return false;
			}
			boolean knownCave = lastEscapeSpot != null && tick - lastEscapeTick < KNOWN_CAVE_TICKS
					&& entity.blockPosition().distSqr(lastEscapeSpot) <= 144;
			if (++undergroundTicks < (knownCave ? LOST_AGAIN_AFTER_TICKS : LOST_AFTER_TICKS)) {
				return false;
			}
			// Walking out of a cave is unremarkable; only having to dig gets noticed.
			startWalk(world, tick);
			return true;
		}
		if (mode == Mode.WALK) {
			if (entity.position().subtract(walkAnchor).horizontalDistanceSqr() > 9.0) {
				walkAnchor = entity.position();
				modeStartTick = tick;
			} else if (tick - modeStartTick > WALK_PATIENCE_TICKS) {
				startDig(world, mind, tick, log);
			}
			return true;
		}
		if (tick >= nextDigTick) {
			digStep(world, mind, tick, log);
		}
		return true;
	}

	private void startWalk(ServerLevel world, long tick) {
		mode = Mode.WALK;
		modeStartTick = tick;
		walkAnchor = entity.position();
		BlockPos feet = entity.blockPosition();
		for (int distance : new int[] {3, 6, 12, 18, 24}) {
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				int x = feet.getX() + dir.getStepX() * distance;
				int z = feet.getZ() + dir.getStepZ() * distance;
				BlockPos open = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
				if (!world.getFluidState(open.below()).isEmpty()) {
					continue;
				}
				Path path = entity.getNavigation().createPath(open, 1);
				if (path != null && path.canReach()) {
					entity.getNavigation().moveTo(path, SPEED);
					return;
				}
			}
		}
		// Nowhere to walk to: skip straight to digging on the next tick.
		modeStartTick = tick - WALK_PATIENCE_TICKS - 1;
	}

	private void startDig(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		mode = Mode.DIG;
		digSteps = 0;
		nextDigTick = tick;
		entity.getNavigation().stop();
		// Head toward wherever the surface is lowest nearby: the shortest climb to daylight.
		BlockPos feet = entity.blockPosition();
		int best = Integer.MAX_VALUE;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos probe = feet.relative(dir, 6);
			int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe.getX(), probe.getZ());
			if (surface < best) {
				best = surface;
				digDirection = dir;
			}
		}
		Crafting.hold(entity, mind, Crafting.Tool.PICKAXE);
	}

	/** One step of the staircase: clear the way one forward and one up, then climb into it. */
	private void digStep(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		if (digSteps++ >= MAX_DIG_STEPS) {
			// Give up for now; it will be noticed as lost again later and start afresh.
			mode = Mode.NONE;
			undergroundTicks = 0;
			return;
		}
		// Back under the same overhang it only just got out from: not news a second time.
		boolean sameCaveAgain = lastEscapeSpot != null && tick - lastEscapeTick < KNOWN_CAVE_TICKS
				&& entity.blockPosition().distSqr(lastEscapeSpot) <= 144;
		if (digSteps == ANNOUNCE_AFTER_STEPS && !announced && !isClimbing() && !sameCaveAgain) {
			// A step or two out from under an overhang isn't news; a real climb out of a cave is.
			announced = true;
			boolean pick = Crafting.best(mind, Crafting.Tool.PICKAXE).isPresent();
			mind.perceive(tick, (onTrip ? "I was done mining and found no way out, so I started digging my way up"
					: "I was lost underground with no way out, so I started digging my way up")
					+ (pick ? " with my pickaxe." : " with my bare hands."), 0.5, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + (onTrip ? " finished mining and started digging back up toward daylight"
							: " was lost underground and started digging toward daylight")
							+ (pick ? "." : " with bare hands."), List.of());
		}
		BlockPos feet = entity.blockPosition();
		for (int turn = 0; turn < 4; turn++) {
			Direction dir = turn == 0 ? digDirection : turn == 1 ? digDirection.getClockWise()
					: turn == 2 ? digDirection.getCounterClockWise() : digDirection.getOpposite();
			BlockPos step = feet.relative(dir).above();
			List<BlockPos> clear = List.of(feet.above(2), step, step.above());
			if (!world.getBlockState(step.below()).isFaceSturdy(world, step.below(), Direction.UP)) {
				continue; // nothing to stand on: an open drop isn't a staircase
			}
			if (!clear.stream().allMatch(p -> diggable(world.getBlockState(p)) && !nearLiquid(world, p))
					|| world.getBlockState(step.above(2)).getBlock() instanceof FallingBlock
					|| world.getBlockState(feet.above(3)).getBlock() instanceof FallingBlock) {
				continue; // sand or gravel overhead would come down on it
			}
			int effort = 0;
			Optional<String> pick = Crafting.best(mind, Crafting.Tool.PICKAXE);
			for (BlockPos pos : clear) {
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || state.canBeReplaced()) {
					continue;
				}
				boolean stone = state.is(BlockTags.MINEABLE_WITH_PICKAXE);
				effort = Math.max(effort, stone ? (pick.isEmpty() ? 40 : Crafting.isStone(pick.get()) ? 8 : 12) : 10);
				entity.swing(InteractionHand.MAIN_HAND);
				// Stone only yields cobblestone to a pickaxe, as in vanilla; loose dirt and gravel aren't worth carrying.
				boolean keep = stone && pick.isPresent();
				world.destroyBlock(pos, keep, entity);
				if (keep) {
					PhysicalActions.collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
				}
				if (stone && pick.isPresent()) {
					Crafting.wear(entity, mind, pick.get(), tick, log);
				}
			}
			digDirection = dir;
			entity.teleportTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
			entity.resetFallDistance();
			nextDigTick = tick + Math.max(effort, 6);
			return;
		}
		// Boxed in on every side by something it won't dig: wait and look again later.
		mode = Mode.NONE;
		undergroundTicks = LOST_AFTER_TICKS / 2;
	}

	/** Natural ground (or open space) that it's fine to dig through. Never anything built. */
	static boolean diggable(BlockState state) {
		return state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()
				|| state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
				|| state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES);
	}

	private static boolean nearLiquid(ServerLevel world, BlockPos pos) {
		if (!world.getFluidState(pos).isEmpty()) {
			return true;
		}
		for (Direction dir : Direction.values()) {
			if (!world.getFluidState(pos.relative(dir)).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}

