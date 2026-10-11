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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * A real chest at home (one a player could open and look into): what an
 * agent has more of than it wants to carry goes in when it's home, and food
 * comes back out when its pack runs low. Tools, doors and torches stay in
 * the pack; so does enough food and wood for the road.
 */
public final class HomeChest {

	public static final String CHEST = "minecraft:chest";
	/** Eight planks make a chest. */
	static final int CHEST_PLANKS = 8;
	/** A chest is searched for this far from the home's origin. */
	static final int FIND_RADIUS = 6;
	/** Food kept in the pack before the rest goes in the chest, and the most taken back out at once. */
	static final int FOOD_KEPT = 8;
	/** Wood and stone kept in the pack. */
	static final int MATERIAL_KEPT = 64;
	/** Anything else kept in the pack. */
	static final int OTHER_KEPT = 16;
	/** Below this many meals carried, it takes food from the chest. */
	static final int LOW_ON_FOOD = 2;

	private HomeChest() {
	}

	/** How much of this item it keeps on itself; the rest can go in the chest. Tools and the like are never put away. */
	static int keep(String itemId) {
		if (itemId.endsWith("_pickaxe") || itemId.endsWith("_axe") || itemId.endsWith("_shovel") || itemId.endsWith("_hoe")
				|| itemId.endsWith("_sword") || itemId.equals(Crafting.DOOR) || itemId.equals(Crafting.TORCH) || itemId.equals(CHEST) || HomeBed.isBed(itemId) || Armour.isArmour(itemId)
				|| itemId.equals(Enchanting.TABLE) || itemId.equals("minecraft:shield")) {
			return Integer.MAX_VALUE;
		}
		if (ItemKinds.nutrition(itemId) > 0) {
			return FOOD_KEPT;
		}
		if (ItemKinds.isBuildingMaterial(itemId) || ItemKinds.isLog(itemId)) {
			return MATERIAL_KEPT;
		}
		return OTHER_KEPT;
	}

	/** Whether it carries anything it would rather put away. */
	static boolean hasSurplus(AgentMind mind) {
		return mind.possessions().stream().anyMatch(p -> p.quantity() > keep(p.itemId()));
	}

	/** The chest at this home, if there is one. */
	public static Optional<BlockPos> find(ServerLevel world, BlockPos origin) {
		return Verbs.findNear(world, origin, Blocks.CHEST, FIND_RADIUS);
	}

	/**
	 * At home: sets up a chest if it has none and has things to put away (making
	 * one from its planks if need be), then puts its surplus in and, if it's
	 * low on food, takes some out. Returns whether it did anything.
	 */
	public static boolean tend(AgentEntity self, ServerLevel world, AgentMind mind, Home home, BlockPos origin, long tick,
			EventLog log) {
		Optional<BlockPos> chest = find(world, origin);
		if (chest.isEmpty()) {
			if (!hasSurplus(mind)) {
				return false;
			}
			if (mind.countOf(CHEST) == 0) {
				if (Crafting.spareWoodFor(mind) < CHEST_PLANKS || !Crafting.planks(mind, CHEST_PLANKS, tick)) {
					return false;
				}
				mind.receiveItem(tick, CHEST, 1);
			}
			chest = place(self, world, mind, home, origin, tick, log);
			if (chest.isEmpty()) {
				return false;
			}
		}
		if (!(world.getBlockEntity(chest.get()) instanceof Container box)) {
			return false;
		}
		int stored = deposit(box, mind);
		int taken = mind.foodMeals() < LOW_ON_FOOD ? withdrawFood(box, mind, tick) : 0;
		if (stored > 0 || taken > 0) {
			box.setChanged();
			self.swing(InteractionHand.MAIN_HAND);
			if (taken > 0) {
				mind.perceive(tick, "I took " + taken + " food from my chest at home.", 0.3, Set.of());
			} else {
				mind.perceive(tick, "I put " + stored + " things away in my chest at home.", 0.15, Set.of());
			}
			return true;
		}
		return false;
	}

	/** Puts down a chest inside the home (away from the bed and the doorway), or else just beside the door. */
	private static Optional<BlockPos> place(AgentEntity self, ServerLevel world, AgentMind mind, Home home, BlockPos origin,
			long tick, EventLog log) {
		Optional<BlockPos> spot = spotFor(world, home, origin);
		if (spot.isEmpty() || !mind.takeItem(CHEST, 1)) {
			return Optional.empty();
		}
		world.setBlockAndUpdate(spot.get(), Blocks.CHEST.defaultBlockState());
		self.swing(InteractionHand.MAIN_HAND);
		mind.perceive(tick, "I set up a chest at my home to keep what I don't need to carry.", 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " set up a chest at " + home.design().name() + ".", List.of());
		return spot;
	}

	/**
	 * Somewhere at home to put down a block of furniture: a free floor cell
	 * inside, away from the bed and the doorway, or else just beside the door
	 * outside (never in front of it).
	 */
	public static Optional<BlockPos> spotFor(ServerLevel world, Home home, BlockPos origin) {
		Design design = home.design();
		Design.Cell bed = design.bed();
		Design.Cell front = design.entrance();
		BlockPos outside = origin.offset(front.dx(), front.dy(), front.dz());
		List<BlockPos> door = new ArrayList<>();
		for (Design.Cell c : design.doorway()) {
			door.add(origin.offset(c.dx(), c.dy(), c.dz()));
		}
		List<BlockPos> spots = new ArrayList<>();
		for (Design.Cell c : design.interior()) {
			if (c.dy() == 0 && !(c.dx() == bed.dx() && c.dz() == bed.dz())) {
				spots.add(origin.offset(c.dx(), c.dy(), c.dz()));
			}
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			spots.add(outside.relative(side));
		}
		for (BlockPos spot : spots) {
			if (door.contains(spot) || spot.equals(outside) || !world.getBlockState(spot).isAir()
					|| !world.getBlockState(spot.below()).isFaceSturdy(world, spot.below(), Direction.UP)
					|| !world.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(spot)).isEmpty()) {
				continue;
			}
			return Optional.of(spot);
		}
		return Optional.empty();
	}

	/** Moves everything beyond what it keeps into the chest, as far as there's room. Returns how many items went in. */
	static int deposit(Container box, AgentMind mind) {
		int moved = 0;
		for (Possession p : List.copyOf(mind.possessions())) {
			int extra = p.quantity() - keep(p.itemId());
			Item item = itemOf(p.itemId());
			if (extra <= 0 || item == null) {
				continue;
			}
			int put = 0;
			while (put < extra) {
				int n = Math.min(extra - put, item.getDefaultMaxStackSize());
				ItemStack left = HopperBlockEntity.addItem(null, box, new ItemStack(item, n), null);
				int in = n - left.getCount();
				put += in;
				if (in < n) {
					break; // the chest is full
				}
			}
			if (put > 0 && mind.takeItem(p.itemId(), put)) {
				moved += put;
			}
		}
		return moved;
	}

	/** Takes food out of the chest, up to {@link #FOOD_KEPT} items. Returns how many. */
	static int withdrawFood(Container box, AgentMind mind, long tick) {
		int taken = 0;
		for (int slot = 0; slot < box.getContainerSize() && taken < FOOD_KEPT; slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String id = ItemKinds.idOf(stack);
			if (ItemKinds.nutrition(id) <= 0) {
				continue;
			}
			ItemStack out = box.removeItem(slot, Math.min(stack.getCount(), FOOD_KEPT - taken));
			if (!out.isEmpty()) {
				mind.receiveItem(tick, id, out.getCount());
				taken += out.getCount();
			}
		}
		return taken;
	}

	private static Item itemOf(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
	}
}
