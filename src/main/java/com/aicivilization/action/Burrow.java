package com.aicivilization.action;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.RecipeBook;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Digging in for the night: a hole two deep where it stands, closed over
 * its head with what came out of it, the way anyone caught out in the open
 * in Minecraft lives to see morning. Nobody starts out knowing it works
 * ({@link RecipeBook#BURROWING}); an agent learns it by trying it and
 * coming out alive, or hears of it from someone who has. Only soft ground
 * (what a shovel digs) can be dug by hand, the walls around the hole must
 * be solid, and there must be ground under it, not a cave.
 */
public final class Burrow {

	/** Only the curious try it unprompted; how likely, at most, before anyone has done it. */
	static final double UNTRIED_CHANCE = 0.6;
	/** Having heard it works, this likely to try. */
	static final double HEARD_CHANCE = 0.7;

	/** Where it dug in and what it took out, to put back in the morning. */
	public record Dug(BlockPos top, BlockState upper, BlockState lower) {
	}

	private Burrow() {
	}

	/** Whether it would dig in rather than just get away: it knows to, has heard of it, or tries out of curiosity. */
	public static boolean wouldTry(AgentMind mind, double roll) {
		RecipeBook book = mind.recipeBook();
		if (book.knowsPractice(RecipeBook.BURROWING)) {
			return true;
		}
		if (book.heardOfPractice(RecipeBook.BURROWING)) {
			return roll < HEARD_CHANCE;
		}
		return roll < mind.personality().curiosity() * UNTRIED_CHANCE;
	}

	/** Where it stands or a step away: soft ground two deep, solid walls, solid floor, open sky to climb out to. */
	public static Optional<BlockPos> findSpot(ServerLevel world, BlockPos feet) {
		if (suits(world, feet)) {
			return Optional.of(feet.immutable());
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos next = feet.relative(side);
			if (suits(world, next)) {
				return Optional.of(next.immutable());
			}
		}
		return Optional.empty();
	}

	/** {@code top} is the air block it stands in; the two below are dug out. */
	static boolean suits(ServerLevel world, BlockPos top) {
		if (!world.getBlockState(top).isAir() || !world.getBlockState(top.above()).isAir()) {
			return false;
		}
		for (int down = 1; down <= 2; down++) {
			BlockPos p = top.below(down);
			BlockState s = world.getBlockState(p);
			if (!s.is(BlockTags.MINEABLE_WITH_SHOVEL) || !world.getFluidState(p).isEmpty()) {
				return false;
			}
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos wall = p.relative(side);
				if (!world.getBlockState(wall).isSolidRender() || !world.getFluidState(wall).isEmpty()) {
					return false;
				}
			}
		}
		BlockPos floor = top.below(3);
		return world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP);
	}

	/** Digs the hole, steps down into it, and closes it over. */
	public static Dug dig(Entity self, ServerLevel world, BlockPos top) {
		BlockState upper = world.getBlockState(top.below());
		BlockState lower = world.getBlockState(top.below(2));
		world.destroyBlock(top.below(), false, self);
		world.destroyBlock(top.below(2), false, self);
		self.teleportTo(top.getX() + 0.5, top.getY() - 2, top.getZ() + 0.5);
		world.setBlockAndUpdate(top, upper);
		return new Dug(top.immutable(), upper, lower);
	}

	/** Opens the hole, climbs out, and fills it back in. */
	public static void emerge(Entity self, ServerLevel world, Dug dug) {
		BlockPos top = dug.top();
		if (world.getBlockState(top).equals(dug.upper())) {
			world.destroyBlock(top, false, self);
		}
		self.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
		if (world.getBlockState(top.below()).isAir()) {
			world.setBlockAndUpdate(top.below(2), dug.lower());
			world.setBlockAndUpdate(top.below(), dug.upper());
		}
	}
}
