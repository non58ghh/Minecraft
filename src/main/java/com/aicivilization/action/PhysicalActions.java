package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.Heightmap;
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
 * a player's log house is safe); agents build with planks (a log is split
 * into four as it's placed) and keep a little clear of trees, so they don't
 * take their own homes apart; and the pick-up only takes items that
 * dropped a moment ago, so nobody's lost items get swept up.
 */
public final class PhysicalActions {

	/**
	 * How far an agent looks for a tree. A settlement clears the trees right
	 * around it within days and the ground is rarely flat, so this reaches
	 * well out and a few blocks up and down; higher than a few blocks is a
	 * trunk it couldn't reach anyway.
	 */
	private static final int LOG_SCAN_XZ = 24;
	private static final int LOG_SCAN_UP = 6;
	private static final int LOG_SCAN_DOWN = 6;
	private static final int TREE_LEAF_RADIUS = 4;
	/** Buildings are planks, never logs, so a tree nearby can't get them chopped; this just keeps leaves out. */
	private static final int SITE_TREE_CLEARANCE = 2;
	private static final int SITE_ATTEMPTS = 40;
	/** Carrying this much wood, an agent that finds no clearing builds among the trees rather than chop forever. */
	private static final int FOREST_CABIN_BLOCKS = 64;
	private static final int SITE_RADIUS = 12;
	private static final int FRESH_DROP_TICKS = 40;
	private static final double DROP_PICKUP_RADIUS = 3.5;
	private static final double HUNGRY_BELOW = 0.55;
	/** Below this, raw meat is eaten rather than kept for cooking. */
	private static final double RAW_IS_FINE_BELOW = 0.3;
	private static final double FOOD_PER_NUTRITION = 1.0 / 12.0;
	private static final double SHELTER_SAFETY_GAIN = 0.25;
	private static final double SHELTER_BELONGING_GAIN = 0.2;
	private static final int PLANKS_PER_LOG = 4;
	private static final int STONE_SCAN = 8;
	/** Coal worth keeping on hand for cooking. */
	private static final int COAL_WANTED = 8;
	/** How far down it looks for ore: into a cave below, not just at its feet. */
	private static final int MINE_DOWN = 12;
	/** How far around it looks for ore on cave walls. */
	private static final int ORE_SCAN = 12;
	private static final int SCRAMBLE_REACH = 6;
	private static final int SCRAMBLE_DEPTH = 48;

	/** What the world currently offers this agent, found once per decision. */
	public record Opportunities(Optional<BlockPos> log, Optional<BlockPos> shelterSite, int buildingBlocks,
			Optional<BlockPos> stone) {
	}

	private PhysicalActions() {
	}

	// -- looking ------------------------------------------------------------

	/**
	 * Scans the area around {@code self}. {@code activeSite} is the shelter
	 * the agent is already building, if any; it is kept until finished (but abandoned
	 * if the site gets blocked).
	 */
	public static Opportunities scan(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos activeSite, Design design,
			boolean lookForNewSite) {
		int blocks = countBuildingBlocks(mind);
		Optional<BlockPos> log = findNearestNaturalLog(self, world);

		Optional<BlockPos> site = Optional.empty();
		// Check if active shelter site is still valid (not blocked by someone else).
		if (activeSite != null) {
			List<BlockPos> remaining = remainingCells(world, activeSite, design);
			if (!remaining.isEmpty() && canContinue(world, activeSite, design)) {
				// Site is still buildable; continue on it.
				site = Optional.of(activeSite);
			}
			// If site is now blocked (fits returned false), abandon it silently.
		}
		if (site.isEmpty() && lookForNewSite && blocks >= 6) {
			site = findShelterSite(self, world, design, false);
			if (site.isEmpty() && blocks >= FOREST_CABIN_BLOCKS) {
				// Deep in the woods with wood to spare: a cabin among the trees will do.
				site = findShelterSite(self, world, design, true);
			}
		}
		// Starting a shelter takes a few blocks; carrying one on needs just one.
		boolean canBuild = site.isPresent() && (activeSite != null ? blocks >= 1 : blocks >= 6);
		// With a pickaxe: ore it can see (in a cave or on a hillside) is worth going for, and a little
		// stone too while it still lacks stone tools. This is what takes an agent underground on purpose.
		Optional<BlockPos> mine = Crafting.best(mind, Crafting.Tool.PICKAXE).isPresent()
				? findMineable(self, world, mind) : Optional.empty();
		return new Opportunities(log, canBuild ? site : Optional.empty(), blocks, mine);
	}

	private static boolean wantsStone(AgentMind mind) {
		return mind.countOf("minecraft:cobblestone") < 3
				&& !(Crafting.best(mind, Crafting.Tool.PICKAXE).map(Crafting::isStone).orElse(false)
						&& Crafting.best(mind, Crafting.Tool.AXE).map(Crafting::isStone).orElse(false)
						&& Crafting.best(mind, Crafting.Tool.SWORD).map(Crafting::isStone).orElse(false));
	}

	/** Ore it can mine with the pickaxe it has (iron and copper need stone), as in vanilla. */
	private static boolean isOre(BlockState state, boolean stonePick) {
		return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
				|| stonePick && (state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES));
	}

	/**
	 * Ore it has a use for right now: coal is furnace fuel, worth a small
	 * supply. Iron and copper have no use yet (no smelting), so they're left.
	 */
	private static boolean wantsOre(BlockState state, AgentMind mind) {
		return (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) && mind.countOf("minecraft:coal") < COAL_WANTED;
	}

	/** Something worth mining right now: stone or ore this agent can take with what it carries. */
	public static boolean isMineTarget(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
				|| state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES);
	}

	/**
	 * The nearest ore with a face open to the air (so it can be reached without tunnelling),
	 * else, while it wants cobblestone, the nearest stone with open air above it.
	 */
	private static Optional<BlockPos> findMineable(AgentEntity self, ServerLevel world, AgentMind mind) {
		boolean stonePick = Crafting.best(mind, Crafting.Tool.PICKAXE).map(Crafting::isStone).orElse(false);
		boolean wantStone = wantsStone(mind);
		BlockPos base = self.blockPosition();
		Optional<BlockPos> ore = Optional.empty();
		Optional<BlockPos> stone = Optional.empty();
		double oreDist = Double.MAX_VALUE;
		double stoneDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-ORE_SCAN, -MINE_DOWN, -ORE_SCAN), base.offset(ORE_SCAN, 3, ORE_SCAN))) {
			BlockState state = world.getBlockState(pos);
			double dist = pos.distSqr(base);
			if (isOre(state, stonePick) && wantsOre(state, mind)) {
				if (dist < oreDist && exposed(world, pos)) {
					ore = Optional.of(pos.immutable());
					oreDist = dist;
				}
			} else if (wantStone && state.is(Blocks.STONE) && dist < stoneDist && dist <= STONE_SCAN * STONE_SCAN
					// From a hill face at or above its feet: digging down only leaves pits others fall into.
					&& pos.getY() >= base.getY()
					&& world.getBlockState(pos.above()).isAir()) {
				stone = Optional.of(pos.immutable());
				stoneDist = dist;
			}
		}
		return ore.isPresent() ? ore : stone;
	}

	private static boolean exposed(ServerLevel world, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (world.getBlockState(pos.relative(dir)).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** Mines one block of stone or ore with a pickaxe, keeping what drops (cobblestone, coal, raw iron...). */
	public static boolean mine(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, long tick, EventLog log) {
		Optional<String> pick = Crafting.best(mind, Crafting.Tool.PICKAXE);
		BlockState state = world.getBlockState(pos);
		if (pick.isEmpty() || !isMineTarget(state)
				|| !state.is(Blocks.STONE) && !isOre(state, Crafting.isStone(pick.get()))) {
			return false;
		}
		String what = ItemKinds.displayName(ItemKinds.idOf(state.getBlock().asItem().getDefaultInstance()));
		self.swing(InteractionHand.MAIN_HAND);
		if (!world.destroyBlock(pos, true, self)) {
			return false;
		}
		int got = collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
		Crafting.wear(self, mind, pick.get(), tick, log);
		mind.perceive(tick, "I mined some " + what + " and took " + got + ".", 0.3, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " mined some " + what + ".", List.of());
		return true;
	}

	public static int countBuildingBlocks(AgentMind mind) {
		int total = 0;
		for (Possession possession : mind.possessions()) {
			if (ItemKinds.isBuildingMaterial(possession.itemId())) {
				// A log splits into four planks when it's time to build.
				total += possession.quantity() * (ItemKinds.isLog(possession.itemId())
						&& ItemKinds.planksFor(possession.itemId()).isPresent() ? PLANKS_PER_LOG : 1);
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
			if (!world.getBlockState(pos).is(BlockTags.LOGS)) {
				continue;
			}
			double dist = pos.distSqr(base);
			// The leaf check is the costly part: only for a log nearer than the best so far.
			if (dist < nearestDist && isNaturalLog(world, pos)) {
				nearest = Optional.of(pos.immutable());
				nearestDist = dist;
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

	private static Optional<BlockPos> findShelterSite(AgentEntity self, ServerLevel world, Design design, boolean nearTreesOk) {
		RandomSource random = self.getRandom();
		BlockPos base = self.blockPosition();
		for (int i = 0; i < SITE_ATTEMPTS; i++) {
			int x = base.getX() + random.nextInt(2 * SITE_RADIUS + 1) - SITE_RADIUS;
			int z = base.getZ() + random.nextInt(2 * SITE_RADIUS + 1) - SITE_RADIUS;
			Optional<BlockPos> origin = groundAt(world, x, base.getY(), z);
			if (origin.isPresent() && (nearTreesOk ? fits(world, origin.get(), design) : fitsSafely(world, origin.get(), design))) {
				return origin;
			}
		}
		return Optional.empty();
	}

	/** Checks that a shelter fits and isn't near trees, in one pass. */
	private static boolean fitsSafely(ServerLevel world, BlockPos origin, Design design) {
		if (!fits(world, origin, design)) {
			return false;
		}
		return !hasNaturalLeavesNear(world, origin, SITE_TREE_CLEARANCE + Math.max(design.width(), design.depth()) / 2, design.height() + 1);
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

	private static boolean fits(ServerLevel world, BlockPos origin, Design design) {
		// Uneven ground is fine up to a point: a one-block dip under up to a third of the floor gets a foundation.
		int dips = 0;
		List<Design.Cell> ground = design.groundCells();
		for (Design.Cell cell : ground) {
			BlockPos below = origin.offset(cell.dx(), -1, cell.dz());
			if (world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
				continue;
			}
			if (!fillable(world, below) || ++dips > ground.size() / 3) {
				return false;
			}
		}
		for (Design.Cell cell : design.footprintVolume()) {
			if (!isFree(world.getBlockState(origin.offset(cell.dx(), cell.dy(), cell.dz())))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A half-built (or damaged) building can be carried on while its inside
	 * and doorway are still clear; its own walls are of course not free.
	 */
	private static boolean canContinue(ServerLevel world, BlockPos origin, Design design) {
		for (Design.Cell cell : design.interior()) {
			if (!isFree(world.getBlockState(origin.offset(cell.dx(), cell.dy(), cell.dz())))) {
				return false;
			}
		}
		for (Design.Cell cell : design.doorway()) {
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
	/** Whether a building part-way up can still be carried on: blocks still missing and its inside still clear. */
	public static boolean stillBuildable(ServerLevel world, BlockPos origin, Design design) {
		return !remainingCells(world, origin, design).isEmpty() && canContinue(world, origin, design);
	}

	/** A one-block gap under the floor that a foundation block can fill: empty, dry, with firm ground beneath. */
	private static boolean fillable(ServerLevel world, BlockPos below) {
		BlockState state = world.getBlockState(below);
		BlockPos under = below.below();
		return isFree(state) && state.getFluidState().isEmpty()
				&& world.getBlockState(under).isFaceSturdy(world, under, Direction.UP);
	}

	/** Foundation blocks still needed under the floor, placed before anything else. */
	static List<BlockPos> foundationCells(ServerLevel world, BlockPos origin, Design design) {
		List<BlockPos> cells = new ArrayList<>();
		for (Design.Cell cell : design.groundCells()) {
			BlockPos below = origin.offset(cell.dx(), -1, cell.dz());
			if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP) && fillable(world, below)) {
				cells.add(below);
			}
		}
		return cells;
	}

	public static List<BlockPos> remainingCells(ServerLevel world, BlockPos origin, Design design) {
		List<BlockPos> remaining = new ArrayList<>();
		for (Design.Cell cell : design.solids()) {
			BlockPos pos = origin.offset(cell.dx(), cell.dy(), cell.dz());
			if (isFree(world.getBlockState(pos))) {
				remaining.add(pos);
			}
		}
		return remaining;
	}

	/** Where to stand while building: just outside the doorway. */
	public static Vec3 standingSpot(BlockPos origin, Design design) {
		Design.Cell front = design.entrance();
		return Vec3.atBottomCenterOf(origin.offset(front.dx(), front.dy(), front.dz()));
	}

	/** Where to sleep: a floor cell inside. */
	public static Vec3 bedSpot(BlockPos origin, Design design) {
		Design.Cell bed = design.bed();
		return Vec3.atBottomCenterOf(origin.offset(bed.dx(), bed.dy(), bed.dz()));
	}

	/** Fraction of a building's solid blocks that are missing, 0 (intact) to 1 (gone). */
	public static double damage(ServerLevel world, BlockPos origin, Design design) {
		int total = design.solids().size();
		return total == 0 ? 0 : (double) remainingCells(world, origin, design).size() / total;
	}

	// -- doing --------------------------------------------------------------

	/**
	 * For an agent that can't get anywhere: stranded on a peak with sheer
	 * drops all round, or stuck in a pit or a water hole it can't jump out
	 * of. It scrambles to the nearest open, dry ground within a few blocks,
	 * down a cliff or up to three blocks up and out, the way a person would
	 * climb. Returns whether it found a way.
	 */
	public static boolean scramble(AgentEntity self, ServerLevel world, AgentMind mind, long tick, EventLog log) {
		BlockPos here = self.blockPosition();
		BlockPos best = null;
		int bestDist = Integer.MAX_VALUE;
		for (int dx = -SCRAMBLE_REACH; dx <= SCRAMBLE_REACH; dx++) {
			for (int dz = -SCRAMBLE_REACH; dz <= SCRAMBLE_REACH; dz++) {
				int dist = dx * dx + dz * dz;
				if (dist == 0 || dist >= bestDist) {
					continue;
				}
				int x = here.getX() + dx;
				int z = here.getZ() + dz;
				BlockPos stand = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
				BlockPos floor = stand.below();
				BlockState ground = world.getBlockState(floor);
				if (stand.getY() > here.getY() + 3 || here.getY() - stand.getY() > SCRAMBLE_DEPTH
						|| !ground.isFaceSturdy(world, floor, Direction.UP) || !ground.getFluidState().isEmpty()
						|| ground.is(BlockTags.LOGS) || !world.getBlockState(stand).isAir()
						|| !world.getBlockState(stand.above()).isAir()) {
					continue;
				}
				best = stand;
				bestDist = dist;
			}
		}
		if (best == null) {
			return false;
		}
		int drop = here.getY() - best.getY();
		self.getNavigation().stop();
		self.teleportTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
		self.resetFallDistance();
		String what = drop > 3 ? "clambered " + drop + " blocks down a cliff" : "scrambled out of a spot they were stuck in";
		mind.perceive(tick, drop > 3 ? "I was stranded up high and had to clamber " + drop + " blocks down a cliff."
				: "I was stuck and had to scramble my way out.", 0.15, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " was stuck and " + what + ".", List.of());
		return true;
	}

	/** Breaks a natural log and picks up what drops. Returns whether a log came down. */
	public static boolean chop(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, long tick, EventLog log) {
		if (!isNaturalLog(world, pos)) {
			return false;
		}
		// "oak log" -> "an oak tree"
		String kind = ItemKinds.displayName(ItemKinds.idOf(world.getBlockState(pos).getBlock().asItem().getDefaultInstance()))
				.replaceAll(" (log|wood|stem|hyphae)$", "");
		String tree = ("aeiou".indexOf(kind.isEmpty() ? 'x' : kind.charAt(0)) >= 0 ? "an " : "a ") + kind + " tree";
		self.swing(InteractionHand.MAIN_HAND);
		// The whole tree comes down, not just the log within reach: no trunk is left hanging in the air.
		// What it can't carry falls where it stood. The leaves are left to wither as they would.
		int got = 0;
		int felled = 0;
		for (BlockPos part : treeLogs(world, pos)) {
			String id = ItemKinds.idOf(world.getBlockState(part).getBlock().asItem().getDefaultInstance());
			boolean room = mind.countOf(id) < carryLimit(id);
			if (!world.destroyBlock(part, !room, self)) {
				continue;
			}
			felled++;
			if (room) {
				mind.receiveItem(tick, id, 1);
				got++;
			}
		}
		if (felled == 0) {
			return false;
		}
		Crafting.best(mind, Crafting.Tool.AXE).ifPresent(axe -> Crafting.wear(self, mind, axe, tick, log));
		mind.perceive(tick, "I chopped down " + tree + " and took " + got + (got == 1 ? " log." : " logs."), 0.2, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " chopped down " + tree + ".", List.of());
		return true;
	}

	/** Most logs in one tree; past this it's more likely a build than a tree. */
	private static final int MAX_TREE_LOGS = 96;
	/** How far a tree's logs reach from its trunk (branches of acacia, dark oak, big jungle trees). */
	private static final int TREE_REACH_XZ = 6;
	private static final int TREE_REACH_UP = 32;

	/**
	 * The logs of the tree {@code base} is part of: every log joined to it
	 * (diagonally too, for branches), close to the trunk and no lower than
	 * a block below it. Base first, then upward.
	 */
	static List<BlockPos> treeLogs(ServerLevel world, BlockPos base) {
		List<BlockPos> found = new java.util.ArrayList<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
		todo.add(base.immutable());
		seen.add(base.immutable());
		while (!todo.isEmpty() && found.size() < MAX_TREE_LOGS) {
			BlockPos at = todo.poll();
			if (!world.getBlockState(at).is(BlockTags.LOGS)) {
				continue;
			}
			found.add(at);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos next = at.offset(dx, dy, dz);
						if (Math.abs(next.getX() - base.getX()) > TREE_REACH_XZ || Math.abs(next.getZ() - base.getZ()) > TREE_REACH_XZ
								|| next.getY() < base.getY() - 1 || next.getY() > base.getY() + TREE_REACH_UP || !seen.add(next)) {
							continue;
						}
						todo.add(next);
					}
				}
			}
		}
		found.sort(java.util.Comparator.comparingInt(BlockPos::getY));
		return found;
	}

	/** Most of one kind of thing an agent bothers to carry; building materials are wanted in bulk. */
	public static int carryLimit(String itemId) {
		return ItemKinds.isBuildingMaterial(itemId) || ItemKinds.isLog(itemId) ? 256 : 64;
	}

	/** Picks up the items a kill or a broken block just dropped. Returns how many items were taken. */
	public static int collectFreshDrops(ServerLevel world, AgentMind mind, Vec3 around, long tick) {
		AABB box = new AABB(around, around).inflate(DROP_PICKUP_RADIUS);
		int taken = 0;
		for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, box, e -> e.tickCount <= FRESH_DROP_TICKS)) {
			ItemStack stack = drop.getItem();
			String id = ItemKinds.idOf(stack);
			// Nobody carries 790 seeds: past a sensible load, the rest is left where it fell.
			int room = carryLimit(id) - mind.countOf(id);
			if (room <= 0) {
				continue;
			}
			int take = Math.min(room, stack.getCount());
			mind.receiveItem(tick, id, take);
			taken += take;
			if (take == stack.getCount()) {
				drop.discard();
			} else {
				stack.shrink(take);
			}
		}
		return taken;
	}

	/** One swing at {@code target}; a sword in hand hits harder, and wears. */
	public static void attack(AgentEntity self, ServerLevel world, AgentMind mind, Animal target, long tick, EventLog log) {
		self.swing(InteractionHand.MAIN_HAND);
		self.doHurtTarget(world, target);
		Crafting.best(mind, Crafting.Tool.SWORD).ifPresent(sword -> Crafting.wear(self, mind, sword, tick, log));
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
		// The most filling thing it has; raw meat is saved for the furnace unless it's getting desperate.
		boolean desperate = mind.needs().food() < RAW_IS_FINE_BELOW;
		String best = null;
		int bestNutrition = 0;
		for (Possession possession : mind.possessions()) {
			int nutrition = ItemKinds.nutrition(possession.itemId());
			if (nutrition > bestNutrition && (desperate || !Cooking.isCookable(possession.itemId()))) {
				best = possession.itemId();
				bestNutrition = nutrition;
			}
		}
		if (best == null || !mind.takeItem(best, 1)) {
			return false;
		}
		mind.needs().adjustFood(bestNutrition * FOOD_PER_NUTRITION);
		String name = ItemKinds.displayName(best);
		self.swing(InteractionHand.MAIN_HAND);
		mind.perceive(tick, "I ate some " + name + ".", 0.3, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " ate some " + name + ".",
				List.of(Cause.needState("food", mind.needs().food())));
		return true;
	}

	/**
	 * Places up to {@code maxBlocks} blocks of the shelter at {@code origin},
	 * using logs or planks from the pack. Returns how many went down; when the
	 * last one does, the shelter counts as finished. Modifies {@code shelterState}
	 * to indicate completion.
	 */
	public static class ShelterBuildResult {
		public final int placed;
		public final boolean completed;
		public ShelterBuildResult(int placed, boolean completed) {
			this.placed = placed;
			this.completed = completed;
		}
	}

	public static ShelterBuildResult build(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos origin, Design design,
			int maxBlocks, long tick, EventLog log) {
		int placed = 0;
		List<BlockPos> remaining = new ArrayList<>(foundationCells(world, origin, design));
		remaining.addAll(remainingCells(world, origin, design));
		for (BlockPos pos : remaining) {
			if (placed >= maxBlocks) {
				break;
			}
			Optional<String> material = pickMaterial(mind, tick);
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
		boolean completed = false;
		if (placed > 0) {
			self.swing(InteractionHand.MAIN_HAND);
			mind.perceive(tick, "I put up " + placed + " more blocks of my " + design.kind() + ".", 0.25, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " placed " + placed + " blocks of " + withArticle(design.name()) + ".", List.of());
			// Check completion: did we place the last block?
			if (placed == remaining.size()) {
				completed = true;
				mind.needs().adjustSafety(SHELTER_SAFETY_GAIN);
				mind.needs().adjustBelonging(SHELTER_BELONGING_GAIN);
				mind.perceive(tick, "I finished building " + withArticle(design.name()) + ".", 0.8, Set.of());
				log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
						mind.identity().name() + " finished building " + withArticle(design.name()) + ".", List.of());
			}
		}
		return new ShelterBuildResult(placed, completed);
	}

	/** Planks first; when there are none left, a log is split into four, as at a crafting table. */
	/** "a hut", but "Idris's homestead". */
	public static String withArticle(String designName) {
		if (designName.contains("'s ")) {
			return designName;
		}
		return ("aeiou".indexOf(Character.toLowerCase(designName.charAt(0))) >= 0 ? "an " : "a ") + designName;
	}

	private static Optional<String> pickMaterial(AgentMind mind, long tick) {
		Optional<String> log = Optional.empty();
		for (Possession possession : mind.possessions()) {
			if (possession.quantity() > 0 && ItemKinds.isBuildingMaterial(possession.itemId())) {
				if (!ItemKinds.isLog(possession.itemId())) {
					return Optional.of(possession.itemId());
				}
				log = log.or(() -> Optional.of(possession.itemId()));
			}
		}
		if (log.isEmpty()) {
			return Optional.empty();
		}
		Optional<String> planks = ItemKinds.planksFor(log.get());
		if (planks.isPresent() && mind.takeItem(log.get(), 1)) {
			mind.receiveItem(tick, planks.get(), PLANKS_PER_LOG);
			return planks;
		}
		return log;
	}
}
