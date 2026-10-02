package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Possession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The real, world-changing things an agent can do: break natural trees,
 * pick up what drops, hunt livestock, eat, and place blocks. The decision to
 * do any of them comes from the agent's mind; this class only carries them
 * out against the Minecraft world and reports what happened back as plain
 * facts (a perceived memory and an event).
 *
 * <p>Guard rails, because these now change the world: only logs belonging to
 * natural trees are ever broken (a log with non-persistent leaves nearby, so
 * a player's log house is safe); agents never build near trees, so they
 * don't take their own shelters apart; and the pick-up only takes items that
 * dropped a moment ago, so nobody's lost items get swept up.
 */
public final class PhysicalActions {

	private static final int LOG_SCAN_XZ = 12;
	private static final int LOG_SCAN_UP = 4;
	private static final int LOG_SCAN_DOWN = 2;
	private static final int TREE_LEAF_RADIUS = 4;
	private static final int SITE_TREE_CLEARANCE = 6;
	private static final int SITE_ATTEMPTS = 16;
	private static final int FRESH_DROP_TICKS = 40;
	private static final double DROP_PICKUP_RADIUS = 3.5;
	private static final double HUNGRY_BELOW = 0.55;
	private static final double FOOD_PER_NUTRITION = 1.0 / 12.0;
	private static final double SHELTER_SAFETY_GAIN = 0.25;
	private static final double SHELTER_BELONGING_GAIN = 0.2;

	/** What the world currently offers this agent, found once per decision. */
	public record Opportunities(Optional<BlockPos> log, Optional<BlockPos> shelterSite, int buildingBlocks) {
	}

	private PhysicalActions() {
	}

	// -- looking ------------------------------------------------------------

	/**
	 * Scans the area around {@code self}. {@code activeSite} is the shelter
	 * the agent is already building, if any; it is kept until finished.
	 */
	public static Opportunities scan(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos activeSite) {
		int blocks = countBuildingBlocks(mind);
		Optional<BlockPos> log = findNearestNaturalLog(self, world);

		Optional<BlockPos> site = Optional.empty();
		if (activeSite != null && !remainingCells(world, activeSite).isEmpty()) {
			site = Optional.of(activeSite);
		} else if (blocks >= 6) {
			site = findShelterSite(self, world);
		}
		// Starting a shelter takes a few blocks; carrying one on needs just one.
		boolean canBuild = site.isPresent() && (activeSite != null ? blocks >= 1 : blocks >= 6);
		return new Opportunities(log, canBuild ? site : Optional.empty(), blocks);
	}

	public static int countBuildingBlocks(AgentMind mind) {
		int total = 0;
		for (Possession possession : mind.possessions()) {
			if (ItemKinds.isBuildingMaterial(possession.itemId())) {
				total += possession.quantity();
			}
		}
		return total;
	}

	private static Optional<BlockPos> findNearestNaturalLog(AgentEntity self, ServerLevel world) {
		BlockPos base = self.blockPosition();
		Optional<BlockPos> nearest = Optional.empty();
		double nearestDist = Double.MAX_VALUE;
		// Scan once, keeping only the nearest natural log found, to avoid sorting.
		for (BlockPos pos : BlockPos.betweenClosed(
				base.offset(-LOG_SCAN_XZ, -LOG_SCAN_DOWN, -LOG_SCAN_XZ),
				base.offset(LOG_SCAN_XZ, LOG_SCAN_UP, LOG_SCAN_XZ))) {
			if (world.getBlockState(pos).is(BlockTags.LOGS) && isNaturalLog(world, pos)) {
				double dist = pos.distSqr(base);
				if (dist < nearestDist) {
					nearest = Optional.of(pos.immutable());
					nearestDist = dist;
				}
			}
		}
		return nearest;
	}

	/** A log with natural (non-persistent) leaves close by is part of a real tree. */
	static boolean isNaturalLog(ServerLevel world, BlockPos pos) {
		if (!world.getBlockState(pos).is(BlockTags.LOGS)) {
			return false;
		}
		return hasNaturalLeavesNear(world, pos, TREE_LEAF_RADIUS, 6);
	}

	private static boolean hasNaturalLeavesNear(ServerLevel world, BlockPos center, int radiusXZ, int up) {
		for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-radiusXZ, -1, -radiusXZ), center.offset(radiusXZ, up, radiusXZ))) {
			BlockState state = world.getBlockState(pos);
			if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
					&& !state.getValue(LeavesBlock.PERSISTENT)) {
				return true;
			}
		}
		return false;
	}

	private static Optional<BlockPos> findShelterSite(AgentEntity self, ServerLevel world) {
		RandomSource random = self.getRandom();
		BlockPos base = self.blockPosition();
		for (int i = 0; i < SITE_ATTEMPTS; i++) {
			int x = base.getX() + random.nextInt(17) - 8;
			int z = base.getZ() + random.nextInt(17) - 8;
			Optional<BlockPos> origin = groundAt(world, x, base.getY(), z);
			if (origin.isPresent() && fitsSafely(world, origin.get())) {
				return origin;
			}
		}
		return Optional.empty();
	}

	/** Checks that a shelter fits and isn't near trees, in one pass. */
	private static boolean fitsSafely(ServerLevel world, BlockPos origin) {
		if (!fits(world, origin)) {
			return false;
		}
		return !hasNaturalLeavesNear(world, origin, SITE_TREE_CLEARANCE, 8);
	}

	/** The first empty cell standing on solid ground near {@code y}. */
	private static Optional<BlockPos> groundAt(ServerLevel world, int x, int y, int z) {
		for (int dy = 3; dy >= -3; dy--) {
			BlockPos pos = new BlockPos(x, y + dy, z);
			BlockPos below = pos.below();
			if (world.getBlockState(pos).isAir() && world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	private static boolean fits(ServerLevel world, BlockPos origin) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos below = origin.offset(dx, -1, dz);
				if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
					return false;
				}
			}
		}
		for (ShelterPlan.Cell cell : ShelterPlan.footprintVolume()) {
			if (!isFree(world.getBlockState(origin.offset(cell.dx(), cell.dy(), cell.dz())))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isFree(BlockState state) {
		return state.isAir() || state.canBeReplaced();
	}

	/** Shelter cells that still need a block, in placement order. */
	public static List<BlockPos> remainingCells(ServerLevel world, BlockPos origin) {
		List<BlockPos> remaining = new ArrayList<>();
		for (ShelterPlan.Cell cell : ShelterPlan.cells()) {
			BlockPos pos = origin.offset(cell.dx(), cell.dy(), cell.dz());
			if (isFree(world.getBlockState(pos))) {
				remaining.add(pos);
			}
		}
		return remaining;
	}

	/** Where to stand while building: just outside the doorway. */
	public static Vec3 standingSpot(BlockPos origin) {
		ShelterPlan.Cell front = ShelterPlan.FRONT_OF_DOOR;
		return Vec3.atBottomCenterOf(origin.offset(front.dx(), front.dy(), front.dz()));
	}

	// -- doing --------------------------------------------------------------

	/** Breaks a natural log and picks up what drops. Returns whether a log came down. */
	public static boolean chop(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, long tick, EventLog log) {
		if (!isNaturalLog(world, pos)) {
			return false;
		}
		String name = ItemKinds.displayName(
				ItemKinds.idOf(world.getBlockState(pos).getBlock().asItem().getDefaultInstance()));
		self.swing(InteractionHand.MAIN_HAND);
		if (!world.destroyBlock(pos, true, self)) {
			return false;
		}
		int got = collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
		mind.perceive(tick, "I chopped down a " + name + " and took " + got + ".", 0.2, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " chopped a " + name + ".", List.of());
		return true;
	}

	/** Picks up the items a kill or a broken block just dropped. Returns how many items were taken. */
	public static int collectFreshDrops(ServerLevel world, AgentMind mind, Vec3 around, long tick) {
		AABB box = new AABB(around, around).inflate(DROP_PICKUP_RADIUS);
		int taken = 0;
		for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, box, e -> e.tickCount <= FRESH_DROP_TICKS)) {
			ItemStack stack = drop.getItem();
			mind.receiveItem(tick, ItemKinds.idOf(stack), stack.getCount());
			taken += stack.getCount();
			drop.discard();
		}
		return taken;
	}

	/** One swing at {@code target}. */
	public static void attack(AgentEntity self, ServerLevel world, Animal target) {
		self.swing(InteractionHand.MAIN_HAND);
		self.doHurtTarget(world, target);
	}

	/** Called when a hunt ends in a kill: takes the drops and notes the kill. */
	public static void finishKill(AgentEntity self, ServerLevel world, AgentMind mind, Animal target, long tick, EventLog log) {
		int got = collectFreshDrops(world, mind, target.position(), tick);
		String what = target.getType().getDescription().getString().toLowerCase();
		mind.perceive(tick, "I hunted a " + what + (got > 0 ? " and took " + got + " items." : "."), 0.35, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " hunted a " + what + ".", List.of());
	}

	/** Eats something from the pack if hungry. Returns whether it ate. */
	public static boolean tryEat(AgentEntity self, AgentMind mind, long tick, EventLog log) {
		if (mind.needs().food() > HUNGRY_BELOW) {
			return false;
		}
		for (Possession possession : mind.possessions()) {
			int nutrition = ItemKinds.nutrition(possession.itemId());
			if (nutrition > 0 && mind.takeItem(possession.itemId(), 1)) {
				mind.needs().adjustFood(nutrition * FOOD_PER_NUTRITION);
				String name = ItemKinds.displayName(possession.itemId());
				self.swing(InteractionHand.MAIN_HAND);
				mind.perceive(tick, "I ate some " + name + ".", 0.3, Set.of());
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + " ate some " + name + ".",
						List.of(Cause.needState("food", mind.needs().food())));
				return true;
			}
		}
		return false;
	}

	/**
	 * Places up to {@code maxBlocks} blocks of the shelter at {@code origin},
	 * using logs or planks from the pack. Returns how many went down; when the
	 * last one does, the shelter counts as finished.
	 */
	public static int build(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos origin, int maxBlocks, long tick, EventLog log) {
		int placed = 0;
		for (BlockPos pos : remainingCells(world, origin)) {
			if (placed >= maxBlocks) {
				break;
			}
			Optional<String> material = pickMaterial(mind);
			if (material.isEmpty()) {
				break;
			}
			Optional<BlockState> state = ItemKinds.blockFor(material.get());
			if (state.isEmpty() || !mind.takeItem(material.get(), 1)) {
				break;
			}
			world.setBlock(pos, state.get(), 3);
			placed++;
		}
		if (placed > 0) {
			self.swing(InteractionHand.MAIN_HAND);
			mind.perceive(tick, "I put up " + placed + " more blocks of my shelter.", 0.25, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " placed " + placed + " blocks of a shelter.", List.of());
			if (remainingCells(world, origin).isEmpty()) {
				mind.needs().adjustSafety(SHELTER_SAFETY_GAIN);
				mind.needs().adjustBelonging(SHELTER_BELONGING_GAIN);
				mind.perceive(tick, "I finished building a shelter.", 0.8, Set.of());
				log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
						mind.identity().name() + " finished building a shelter.", List.of());
			}
		}
		return placed;
	}

	private static Optional<String> pickMaterial(AgentMind mind) {
		for (Possession possession : mind.possessions()) {
			if (possession.quantity() > 0 && ItemKinds.isBuildingMaterial(possession.itemId())) {
				return Optional.of(possession.itemId());
			}
		}
		return Optional.empty();
	}
}
