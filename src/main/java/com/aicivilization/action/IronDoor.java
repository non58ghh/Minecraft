package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Possession;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The first piece of redstone most players build: an iron door worked by a
 * button on each side. Nothing without hands opens it; a zombie can't, and
 * can't break it down the way it can a wooden door. An agent fits one in
 * place of its wooden door once it has the door and two buttons, and from
 * then on presses the button to come and go. It's real redstone: the
 * button powers the door for a moment and it swings shut by itself.
 */
public final class IronDoor {

	static final String IRON_DOOR = "minecraft:iron_door";
	/** Close enough to the door to reach its button. */
	private static final double REACH_SQ = 2.5 * 2.5;

	private IronDoor() {
	}

	/** Its home's door: the lower half, if there's a door hung in the doorway. */
	static Optional<BlockPos> door(ServerLevel world, BlockPos origin, Design design) {
		BlockPos lower = null;
		for (Design.Cell c : design.doorway()) {
			BlockPos p = origin.offset(c.dx(), c.dy(), c.dz());
			BlockState st = world.getBlockState(p);
			if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				lower = p;
			}
		}
		return Optional.ofNullable(lower);
	}

	/** A button it carries (stone first), if any. */
	static String button(AgentMind mind) {
		String any = null;
		for (Possession p : mind.possessions()) {
			if (p.itemId().endsWith("_button") && p.quantity() >= 2) {
				if (p.itemId().equals("minecraft:stone_button")) {
					return p.itemId();
				}
				any = p.itemId();
			}
		}
		return any;
	}

	/**
	 * At home with a wooden door, an iron door and two buttons: takes the
	 * wooden door down (keeping it) and fits the iron one, with a button on
	 * the wall beside it outside and in. Returns whether it did.
	 */
	public static boolean fit(AgentEntity self, ServerLevel world, AgentMind mind, Home home, BlockPos origin, long tick,
			EventLog log) {
		String buttonId = button(mind);
		if (mind.countOf(IRON_DOOR) == 0 || buttonId == null) {
			return false;
		}
		Optional<BlockPos> found = door(world, origin, home.design());
		if (found.isEmpty() || !DoorBlock.isWoodenDoor(world.getBlockState(found.get()))) {
			return false;
		}
		BlockPos lower = found.get();
		BlockState old = world.getBlockState(lower);
		Direction out = old.getValue(DoorBlock.FACING);
		Block button = blockOf(buttonId);
		if (!(button instanceof ButtonBlock)) {
			return false;
		}
		// A wall block beside the door at head height, with room for a button on each face.
		BlockPos wall = null;
		for (Direction side : new Direction[] {out.getClockWise(), out.getCounterClockWise()}) {
			BlockPos w = lower.above().relative(side);
			if (world.getBlockState(w).isFaceSturdy(world, w, out) && world.getBlockState(w).isFaceSturdy(world, w, out.getOpposite())
					&& world.getBlockState(w.relative(out)).isAir() && world.getBlockState(w.relative(out.getOpposite())).isAir()) {
				wall = w;
				break;
			}
		}
		if (wall == null || !mind.takeItem(IRON_DOOR, 1)) {
			return false;
		}
		if (!mind.takeItem(buttonId, 2)) {
			mind.receiveItem(tick, IRON_DOOR, 1);
			return false;
		}
		String woodenId = BuiltInRegistries.ITEM.getKey(old.getBlock().asItem()).toString();
		BlockState iron = Blocks.IRON_DOOR.defaultBlockState()
				.setValue(DoorBlock.FACING, out)
				.setValue(DoorBlock.HINGE, old.getValue(DoorBlock.HINGE))
				.setValue(DoorBlock.OPEN, false);
		// Both halves swapped without shape updates in between, so neither finds half a door and pops.
		int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		world.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), quiet);
		world.setBlock(lower, Blocks.AIR.defaultBlockState(), quiet);
		world.setBlock(lower.above(), iron.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), quiet);
		world.setBlock(lower, iron.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
		world.setBlockAndUpdate(wall.relative(out), button.defaultBlockState()
				.setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, out));
		world.setBlockAndUpdate(wall.relative(out.getOpposite()), button.defaultBlockState()
				.setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, out.getOpposite()));
		mind.receiveItem(tick, woodenId, 1);
		self.swing(InteractionHand.MAIN_HAND);
		mind.perceive(tick, "I fitted an iron door with a button on each side. Nothing without hands can open it.", 0.6, Set.of());
		log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()), mind.identity().name()
				+ " fitted an iron door worked by buttons at " + home.design().name() + ": zombies can't open it.", List.of());
		return true;
	}

	/**
	 * Coming or going by an iron door that's shut: it presses the button on
	 * its side to let itself through, unless a monster is close enough to
	 * follow it in. Returns whether it pressed one.
	 */
	public static boolean letThrough(AgentEntity self, ServerLevel world, Home home, BlockPos origin) {
		Optional<BlockPos> found = door(world, origin, home.design());
		if (found.isEmpty()) {
			return false;
		}
		BlockPos lower = found.get();
		BlockState door = world.getBlockState(lower);
		if (!door.is(Blocks.IRON_DOOR) || door.getValue(DoorBlock.OPEN) || self.blockPosition().distSqr(lower) > REACH_SQ
				|| !world.getEntitiesOfClass(Monster.class, self.getBoundingBox().inflate(5), Monster::isAlive).isEmpty()) {
			return false;
		}
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(lower.offset(-2, 0, -2), lower.offset(2, 2, 2))) {
			BlockState st = world.getBlockState(p);
			double d = p.distSqr(self.blockPosition());
			if (st.getBlock() instanceof ButtonBlock && !st.getValue(ButtonBlock.POWERED) && d < bestDist) {
				best = p.immutable();
				bestDist = d;
			}
		}
		if (best == null) {
			return false;
		}
		BlockState st = world.getBlockState(best);
		((ButtonBlock) st.getBlock()).press(st, world, best, null);
		self.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	private static Block blockOf(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}
}
