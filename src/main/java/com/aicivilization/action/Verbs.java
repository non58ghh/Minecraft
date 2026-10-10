package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Planner;
import com.aicivilization.mind.RecipeBook;
import com.aicivilization.world.RecipeCatalog;
import com.aicivilization.world.StationHolds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The general actions a plan is carried out with: place a block, craft at a
 * table, smelt in a furnace (mining is {@link PhysicalActions#mine} and
 * {@link PhysicalActions#chop}). Each says whether it worked and leaves an
 * event behind. Crafting checks the game's own recipe for what comes out;
 * smelting uses a real furnace, which really takes time.
 */
public final class Verbs {

	/** Close enough to use a table or furnace. */
	public static final int STATION_REACH = 4;
	/** Furnace slots, as in vanilla. */
	private static final int INPUT = 0;
	private static final int FUEL = 1;
	private static final int OUTPUT = 2;
	/** Ticks a furnace takes per item, as in vanilla, plus a margin before a hold lapses. */
	private static final int COOK_TICKS = 200;
	private static final int HOLD_MARGIN = 1200;

	public enum SmeltResult {
		/** Its things went in; come back later. */
		LOADED,
		/** It took what was ready. */
		COLLECTED,
		/** Its things are still cooking. */
		WAITING,
		/** Someone else's things are in it. */
		BUSY,
		FAILED
	}

	private Verbs() {
	}

	/** The nearest block of {@code block} within reach that has open air beside it, skipping {@code blocked} spots. */
	public static Optional<BlockPos> findExposed(ServerLevel world, BlockPos center, String block, int radius, int down,
			int up, Predicate<BlockPos> blocked) {
		Block wanted = blockOf(block);
		if (wanted == null) {
			return Optional.empty();
		}
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -down, -radius), center.offset(radius, up, radius))) {
			if (!world.getBlockState(pos).is(wanted) || blocked.test(pos)) {
				continue;
			}
			double d = pos.distSqr(center);
			if (d < bestDist && hasAirBeside(world, pos)) {
				best = pos.immutable();
				bestDist = d;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Like {@link #findExposed} but for any kind of log (or wood block), in a
	 * single pass: for when the wanted kind isn't about and any wood will do.
	 */
	public static Optional<BlockPos> findExposedLog(ServerLevel world, BlockPos center, int radius, int down, int up,
			Predicate<BlockPos> blocked) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -down, -radius), center.offset(radius, up, radius))) {
			if (!world.getBlockState(pos).is(BlockTags.LOGS) || blocked.test(pos)) {
				continue;
			}
			double d = pos.distSqr(center);
			if (d < bestDist && hasAirBeside(world, pos)) {
				best = pos.immutable();
				bestDist = d;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The nearest block of this kind within {@code radius}, if any. */
	public static Optional<BlockPos> findNear(ServerLevel world, BlockPos center, Block block, int radius) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
			if (world.getBlockState(pos).is(block) && pos.distSqr(center) < bestDist) {
				best = pos.immutable();
				bestDist = pos.distSqr(center);
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Puts down a block it carries (a crafting table, a furnace) on firm
	 * ground beside it. Returns where, if it could.
	 */
	public static Optional<BlockPos> place(AgentEntity self, ServerLevel world, AgentMind mind, String itemId, long tick,
			EventLog log) {
		Block block = blockOf(itemId);
		if (block == null || mind.countOf(itemId) <= 0) {
			return Optional.empty();
		}
		BlockPos feet = self.blockPosition();
		for (int r = 1; r <= 2; r++) {
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos spot = feet.relative(dir, r);
				BlockPos below = spot.below();
				if (world.getBlockState(spot).isAir() && world.getBlockState(spot.above()).isAir()
						&& world.getBlockState(below).isFaceSturdy(world, below, Direction.UP) && mind.takeItem(itemId, 1)) {
					world.setBlock(spot, block.defaultBlockState(), 3);
					self.swing(InteractionHand.MAIN_HAND);
					String name = ItemKinds.displayName(itemId);
					mind.perceive(tick, "I set up a " + name + ".", 0.35, Set.of());
					log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
							mind.identity().name() + " set up a " + name + ".", List.of());
					return Optional.of(spot);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Makes a plan's craft step: at a table if the recipe needs one (setting
	 * one up from its pack if none is near), using up the inputs. What comes
	 * out is checked against the game's recipe; returns whether it worked.
	 */
	public static boolean craft(AgentEntity self, ServerLevel world, AgentMind mind, Planner.Step step, long tick,
			EventLog log) {
		BlockPos here = self.blockPosition();
		boolean atTable = false;
		if (step.station() == RecipeBook.Station.TABLE) {
			atTable = findNear(world, here, Blocks.CRAFTING_TABLE, STATION_REACH).isPresent()
					|| place(self, world, mind, Planner.CRAFTING_TABLE, tick, log).isPresent();
			if (!atTable) {
				return false;
			}
		}
		if (RecipeCatalog.get().recipesFor(step.item()).isEmpty()) {
			return false;
		}
		for (Map.Entry<String, Integer> input : step.inputs().entrySet()) {
			if (mind.countOf(input.getKey()) < input.getValue()) {
				return false;
			}
		}
		step.inputs().forEach(mind::takeItem);
		mind.receiveItem(tick, step.item(), step.count());
		self.swing(InteractionHand.MAIN_HAND);
		String what = step.count() + " " + ItemKinds.displayName(step.item());
		mind.perceive(tick, "I made " + what + (atTable ? " at a crafting table." : "."), 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " made " + what + (atTable ? " at a crafting table." : "."), List.of());
		return true;
	}

	/**
	 * A plan's smelt step at the furnace at {@code pos}: takes out what's
	 * ready, or loads its raw things and fuel and holds the furnace while
	 * they cook. Learns the recipe the first time it sees it work.
	 */
	public static SmeltResult smelt(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, Planner.Step step,
			long tick, EventLog log) {
		if (!(world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
			return SmeltResult.FAILED;
		}
		if (StationHolds.heldByOther(pos, mind.identity().id(), tick)) {
			mind.perceive(tick, "Someone else's things were cooking in the furnace, so I left it.", 0.25, Set.of());
			return SmeltResult.BUSY;
		}
		ItemStack out = furnace.getItem(OUTPUT);
		if (!out.isEmpty() && ItemKinds.idOf(out).equals(step.item())) {
			int got = out.getCount();
			mind.receiveItem(tick, step.item(), got);
			furnace.setItem(OUTPUT, ItemStack.EMPTY);
			if (furnace.getItem(INPUT).isEmpty()) {
				StationHolds.release(pos, mind.identity().id());
			}
			String what = got + " " + ItemKinds.displayName(step.item());
			mind.perceive(tick, "I took " + what + " out of the furnace.", 0.45, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " smelted " + what + ".", List.of());
			return SmeltResult.COLLECTED;
		}
		if (!furnace.getItem(INPUT).isEmpty()) {
			return SmeltResult.WAITING;
		}
		String input = step.inputs().keySet().stream().filter(k -> !k.equals(Planner.COAL)).findFirst().orElse(null);
		int coal = step.inputs().getOrDefault(Planner.COAL, 0);
		ItemStack fuel = furnace.getItem(FUEL);
		boolean fuelFits = fuel.isEmpty() || ItemKinds.idOf(fuel).equals(Planner.COAL);
		if (input == null || !fuelFits || mind.countOf(input) < step.times() || mind.countOf(Planner.COAL) < coal) {
			return SmeltResult.FAILED;
		}
		Item inputItem = itemOf(input);
		Item coalItem = itemOf(Planner.COAL);
		if (inputItem == null || coalItem == null) {
			return SmeltResult.FAILED;
		}
		mind.takeItem(input, step.times());
		mind.takeItem(Planner.COAL, coal);
		furnace.setItem(INPUT, new ItemStack(inputItem, step.times()));
		furnace.setItem(FUEL, new ItemStack(coalItem, fuel.getCount() + coal));
		furnace.setChanged();
		StationHolds.hold(pos, mind.identity().id(), tick + (long) step.times() * COOK_TICKS + HOLD_MARGIN);
		self.swing(InteractionHand.MAIN_HAND);
		String what = step.times() + " " + ItemKinds.displayName(input);
		mind.perceive(tick, "I put " + what + " in the furnace to smelt.", 0.35, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " put " + what + " in a furnace to smelt.", List.of());
		return SmeltResult.LOADED;
	}

	private static boolean hasAirBeside(ServerLevel world, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			BlockState state = world.getBlockState(pos.relative(dir));
			if (state.isAir()) {
				return true;
			}
		}
		return false;
	}

	private static Block blockOf(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}

	private static Item itemOf(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
	}
}
