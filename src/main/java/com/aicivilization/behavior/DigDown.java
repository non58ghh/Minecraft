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
 * the ground to the depth where that ore is commonest, then a tunnel along
 * at that depth, looking about for the ore as it goes and mining what it
 * finds. It lights the way with torches when it has them (and makes them
 * from coal it digs up), so nothing spawns in the dark behind it. It stops
 * when it has enough, when it gets hungry or night comes, or when it has
 * gone far enough; the cave escape then brings it back up as the end of a
 * trip, not as being lost.
 */
final class DigDown {

	/** Steps (down and along) before it gives up for this trip. */
	private static final int MAX_STEPS = 160;
	/** A torch every this many steps, when it's dark there. */
	private static final int TORCH_EVERY = 6;
	/** Coal it digs up on the way while it carries fewer than this. */
	private static final int COAL_WANTED = 8;
	/** Blocks around the tunnel it can see and reach into for ore. */
	private static final int LOOK_RADIUS = 3;
	/** Never below this height: lava lakes fill the bottom of the world. */
	private static final int LOWEST_Y = -50;
	private static final double HUNGRY = 0.3;

	private final AgentEntity entity;
	private Block target;
	/** The same ore as it occurs deep down (deepslate iron ore), if there is one. */
	private Block deepTarget;
	private String targetName = "";
	private String wantedItem = "";
	private int wanted;
	private int steps;
	/** The height it tunnels along at, where the ore it wants is commonest. */
	private int depth;
	private boolean tunnelling;
	private int sinceTorch;
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
		depth = depthFor(block);
		tunnelling = false;
		sinceTorch = 0;
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
		// Ore in sight of the tunnel: reach in and take it. Coal too, while it has little: that's torches.
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-LOOK_RADIUS, -1, -LOOK_RADIUS),
				feet.offset(LOOK_RADIUS, 2, LOOK_RADIUS))) {
			BlockState state = world.getBlockState(pos);
			boolean coal = state.is(net.minecraft.world.level.block.Blocks.COAL_ORE)
					|| state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE);
			if ((state.is(target) || state.is(deepTarget) || coal && mind.countOf("minecraft:coal") < COAL_WANTED)
					&& PhysicalActions.mine(entity, world, mind, pos.immutable(), tick, log)) {
				nextTick = tick + 10;
				return true;
			}
		}
		if (!tunnelling && feet.getY() <= depth) {
			tunnelling = true;
			mind.perceive(tick, "I dug down to where " + targetName + " is found and started a tunnel along.", 0.4, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " dug down to the depth of " + targetName + " and started a tunnel.", List.of());
		}
		boolean moved = tunnelling ? stepAlong(world, mind, tick, log) : feet.getY() > LOWEST_Y && stepDown(world, mind, tick, log);
		if (!moved) {
			stop(mind, tick, log, false);
			return false;
		}
		steps++;
		if (++sinceTorch >= TORCH_EVERY && light(world, mind, tick, log)) {
			sinceTorch = 0;
		}
		nextTick = tick + 12;
		return true;
	}

	/**
	 * The height at which to stop going down and tunnel along for this ore:
	 * about where it's commonest, no deeper than {@link #LOWEST_Y} allows.
	 */
	static int depthFor(String block) {
		String b = block.replace("minecraft:", "").replace("deepslate_", "");
		return switch (b) {
			case "coal_ore" -> 48;
			case "copper_ore" -> 40;
			case "iron_ore", "emerald_ore" -> 16;
			case "lapis_ore" -> 0;
			case "gold_ore" -> -16;
			case "redstone_ore", "diamond_ore" -> -48;
			default -> 16;
		};
	}

	/** One step along the tunnel: two blocks high, turning aside at water, lava, a drop or loose sand overhead. */
	private boolean stepAlong(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		BlockPos feet = entity.blockPosition();
		for (int turn = 0; turn < 4; turn++) {
			Direction dir = turn == 0 ? direction : turn == 1 ? direction.getClockWise()
					: turn == 2 ? direction.getCounterClockWise() : direction.getOpposite();
			BlockPos step = feet.relative(dir);
			if (turn == 3 && world.getBlockState(step).isAir()) {
				continue; // back the way it came: that's the end of this tunnel
			}
			if (cut(world, mind, step, List.of(step, step.above()), tick, log)) {
				direction = dir;
				return true;
			}
		}
		return false;
	}

	/**
	 * Puts a torch on the tunnel wall at head height where it's dark, making
	 * some from coal and a stick if it has none. Returns whether it put one up.
	 */
	private boolean light(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		BlockPos head = entity.blockPosition().above();
		if (!world.getBlockState(head).isAir()
				|| world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, head) >= 8) {
			return false;
		}
		for (Direction side : new Direction[] {direction.getClockWise(), direction.getCounterClockWise()}) {
			BlockPos wall = head.relative(side);
			if (!world.getBlockState(wall).isFaceSturdy(world, wall, side.getOpposite())) {
				continue;
			}
			if (mind.countOf(Crafting.TORCH) == 0 && !Crafting.craftTorches(mind, mind.home().isPresent(), tick, log)) {
				return false;
			}
			if (!mind.takeItem(Crafting.TORCH, 1)) {
				return false;
			}
			world.setBlockAndUpdate(head, net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState()
					.setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, side.getOpposite()));
			entity.swing(InteractionHand.MAIN_HAND);
			return true;
		}
		return false;
	}

	private boolean stepDown(ServerLevel world, AgentMind mind, long tick, EventLog log) {
		BlockPos feet = entity.blockPosition();
		for (int turn = 0; turn < 4; turn++) {
			Direction dir = turn == 0 ? direction : turn == 1 ? direction.getClockWise()
					: turn == 2 ? direction.getCounterClockWise() : direction.getOpposite();
			BlockPos step = feet.relative(dir).below();
			if (cut(world, mind, step, List.of(step, step.above(), step.above(2)), tick, log)) {
				direction = dir;
				return true;
			}
		}
		return false;
	}

	/**
	 * Clears {@code clear} and steps into {@code step}, if there's firm floor
	 * under it and no water, lava or loose sand about. Returns whether it did.
	 */
	private boolean cut(ServerLevel world, AgentMind mind, BlockPos step, List<BlockPos> clear, long tick, EventLog log) {
		BlockPos floor = step.below();
		BlockPos top = clear.get(clear.size() - 1).above();
		if (!world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP)
				|| !clear.stream().allMatch(p -> CaveEscape.diggable(world.getBlockState(p)) && !CaveEscape.nearLiquid(world, p))
				|| world.getBlockState(top).getBlock() instanceof FallingBlock) {
			return false; // a drop, water or lava, or sand that would fall in
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
		entity.getNavigation().stop();
		entity.teleportTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5);
		entity.resetFallDistance();
		return true;
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
		if (steps == 0 && !enough) {
			// Never really got going (no room to cut the first step): not a trip worth telling.
			return;
		}
		String how = enough ? "found the " + targetName + " I went down for" : "found no " + targetName;
		String way = tunnelling ? " steps down and along" : " steps down";
		mind.perceive(tick, "I dug " + steps + way + " and " + how + "; time to head back up.", enough ? 0.55 : 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()), mind.identity().name() + " dug " + steps + way
				+ " and " + (enough ? "found the " + targetName + " they wanted." : "found no " + targetName + "."),
				List.of());
	}
}
