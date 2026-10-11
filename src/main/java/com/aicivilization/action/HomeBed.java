package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Possession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * A real bed at home: made from three wool of one colour and three planks,
 * set down inside where there's floor for two blocks, and slept in while the
 * door is shut for the night. Sleeping in it heals faster than lying awake.
 */
public final class HomeBed {

	/** A bed is searched for this far from the home's origin. */
	static final int FIND_RADIUS = 5;

	private HomeBed() {
	}

	/** Whether this item is a bed. */
	static boolean isBed(String itemId) {
		return itemId.startsWith("minecraft:") && itemId.endsWith("_bed");
	}

	/** The head of the bed at this home, if there is one. */
	public static Optional<BlockPos> find(ServerLevel world, BlockPos origin) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-FIND_RADIUS, -2, -FIND_RADIUS), origin.offset(FIND_RADIUS, 2, FIND_RADIUS))) {
			BlockState st = world.getBlockState(pos);
			if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
				double d = pos.distSqr(origin);
				if (d < bestDist) {
					bestDist = d;
					best = pos.immutable();
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * At home with no bed: makes one if it has the wool (or carries one) and
	 * puts it down inside. Returns whether it did.
	 */
	public static boolean tend(AgentEntity self, ServerLevel world, AgentMind mind, Home home, BlockPos origin, long tick,
			EventLog log) {
		if (find(world, origin).isPresent()) {
			return false;
		}
		String item = carried(mind);
		if (item == null) {
			item = Crafting.craftBed(mind, tick, log);
			if (item == null) {
				return false;
			}
		}
		Block block = blockOf(item);
		if (!(block instanceof BedBlock)) {
			return false;
		}
		Design design = home.design();
		List<BlockPos> door = new ArrayList<>();
		for (Design.Cell c : design.doorway()) {
			door.add(origin.offset(c.dx(), c.dy(), c.dz()));
		}
		Design.Cell bedCell = design.bed();
		List<BlockPos> floor = new ArrayList<>();
		floor.add(origin.offset(bedCell.dx(), bedCell.dy(), bedCell.dz()));
		for (Design.Cell c : design.interior()) {
			BlockPos p = origin.offset(c.dx(), c.dy(), c.dz());
			if (c.dy() == 0 && !floor.contains(p)) {
				floor.add(p);
			}
		}
		for (BlockPos head : floor) {
			if (!free(world, head, door)) {
				continue;
			}
			for (Direction toHead : Direction.Plane.HORIZONTAL) {
				BlockPos foot = head.relative(toHead.getOpposite());
				if (!floor.contains(foot) || !free(world, foot, door) || !mind.takeItem(item, 1)) {
					continue;
				}
				BlockState bed = block.defaultBlockState().setValue(BedBlock.FACING, toHead);
				world.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
				world.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
				self.swing(InteractionHand.MAIN_HAND);
				mind.perceive(tick, "I put a bed in my home. I can sleep properly now, and heal while I do.", 0.45, Set.of());
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + " put a bed in " + design.name() + ".", List.of());
				return true;
			}
		}
		return false;
	}

	/** Lies down in the bed at home, if there's one free. Returns whether it's asleep. */
	public static boolean sleep(AgentEntity self, ServerLevel world, BlockPos origin) {
		if (self.isSleeping()) {
			return true;
		}
		Optional<BlockPos> head = find(world, origin);
		if (head.isEmpty() || world.getBlockState(head.get()).getValue(BedBlock.OCCUPIED)) {
			return false;
		}
		self.getNavigation().stop();
		self.startSleeping(head.get());
		return true;
	}

	/** A bed item it carries, if any. */
	private static String carried(AgentMind mind) {
		for (Possession p : mind.possessions()) {
			if (isBed(p.itemId()) && p.quantity() > 0) {
				return p.itemId();
			}
		}
		return null;
	}

	/** Empty floor for half a bed: air on firm ground, not the doorway, nobody else standing there. */
	private static boolean free(ServerLevel world, BlockPos spot, List<BlockPos> door) {
		return !door.contains(spot) && world.getBlockState(spot).isAir() && world.getBlockState(spot.above()).isAir()
				&& world.getBlockState(spot.below()).isFaceSturdy(world, spot.below(), Direction.UP);
	}

	private static Block blockOf(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}
}
