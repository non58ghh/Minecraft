package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Possession;
import com.aicivilization.perception.Huntable;
import com.aicivilization.population.PopulationRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Food that doesn't come from hunting: picking ripe berries and crops,
 * planting fields, and feeding pairs of animals so they breed. Like
 * {@link PhysicalActions}, the decision comes from the agent's mind; this
 * class only finds what the world offers and carries actions out.
 *
 * <p>Guard rails: a harvested crop is replanted when the agent has the seed,
 * so village farms aren't stripped; new fields only go on open grass or dirt
 * under the sky; only ordinary adult livestock is fed.
 */
public final class FoodActions {

	private static final int SCAN_XZ = 12;
	private static final int SCAN_UP = 3;
	private static final int SCAN_DOWN = 2;
	private static final int PLOT_XZ = 8;
	/** Farmland within this many blocks of water is kept moist (vanilla rule). */
	private static final int WATER_REACH = 4;
	private static final int TEND_RADIUS = 3;
	private static final double ANIMAL_RADIUS = 10.0;
	private static final double FEED_REACH = 4.0;

	/** Crop block -> what it is planted from. */
	private static final Map<Block, Item> SEED_OF = Map.of(
			Blocks.WHEAT, Items.WHEAT_SEEDS,
			Blocks.CARROTS, Items.CARROT,
			Blocks.POTATOES, Items.POTATO,
			Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

	private static final Map<Block, String> CROP_NAME = Map.of(
			Blocks.WHEAT, "wheat", Blocks.CARROTS, "carrots", Blocks.POTATOES, "potatoes", Blocks.BEETROOTS, "beetroots");

	/** Planting material -> the crop it grows, in order of preference. */
	private static final Map<Item, Block> CROP_OF = Map.of(
			Items.WHEAT_SEEDS, Blocks.WHEAT,
			Items.CARROT, Blocks.CARROTS,
			Items.POTATO, Blocks.POTATOES,
			Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

	/** Feed each livestock type accepts. */
	private static final List<Item> FEEDS = List.of(Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS);

	/** A pair of animals of one kind that would breed if fed {@code feedId}. */
	public record BreedPair(Animal a, Animal b, String feedId) {
	}

	/** What the world offers for food besides hunting, found once per decision. */
	public record FoodOpportunities(Optional<BlockPos> ripePlant, Optional<BlockPos> plot,
			Optional<BreedPair> breedPair, Optional<BlockPos> seedGrass) {

		public boolean anyFarming() {
			return ripePlant.isPresent() || plot.isPresent() || breedPair.isPresent() || seedGrass.isPresent();
		}
	}

	private FoodActions() {
	}

	// -- looking ------------------------------------------------------------

	/**
	 * @param fieldAnchor a field this agent planted earlier, if any nearby;
	 *                    new plots are looked for around it so fields grow
	 *                    together instead of single plants scattered about
	 */
	public static FoodOpportunities scan(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos fieldAnchor) {
		BlockPos base = self.blockPosition();
		BlockPos ripe = null;
		BlockPos grass = null;
		double ripeDist = Double.MAX_VALUE;
		double grassDist = Double.MAX_VALUE;
		boolean hasPlantable = plantable(mind).isPresent();
		for (BlockPos pos : BlockPos.betweenClosed(
				base.offset(-SCAN_XZ, -SCAN_DOWN, -SCAN_XZ), base.offset(SCAN_XZ, SCAN_UP, SCAN_XZ))) {
			BlockState state = world.getBlockState(pos);
			double dist = pos.distSqr(base);
			if (dist < ripeDist && isRipe(state)) {
				ripe = pos.immutable();
				ripeDist = dist;
			} else if (!hasPlantable && dist < grassDist
					&& (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))) {
				grass = pos.immutable();
				grassDist = dist;
			}
		}
		Optional<BlockPos> plot = hasPlantable ? findPlot(world, fieldAnchor != null ? fieldAnchor : base) : Optional.empty();
		return new FoodOpportunities(Optional.ofNullable(ripe), plot, findBreedPair(self, world, mind),
				Optional.ofNullable(grass));
	}

	static boolean isRipe(BlockState state) {
		if (state.getBlock() instanceof CropBlock crop) {
			return crop.isMaxAge(state);
		}
		if (state.is(Blocks.SWEET_BERRY_BUSH)) {
			return state.getValue(SweetBerryBushBlock.AGE) >= 2;
		}
		return false;
	}

	/**
	 * Empty farmland first (an existing field), else open grass or dirt under
	 * the sky. Ground within reach of water is preferred: watered farmland
	 * grows crops several times faster than dry.
	 */
	private static Optional<BlockPos> findPlot(ServerLevel world, BlockPos base) {
		List<BlockPos> water = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(
				base.offset(-PLOT_XZ - WATER_REACH, -3, -PLOT_XZ - WATER_REACH), base.offset(PLOT_XZ + WATER_REACH, 1, PLOT_XZ + WATER_REACH))) {
			if (world.getFluidState(pos).is(FluidTags.WATER)) {
				water.add(pos.immutable());
			}
		}
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-PLOT_XZ, -2, -PLOT_XZ), base.offset(PLOT_XZ, 1, PLOT_XZ))) {
			BlockState state = world.getBlockState(pos);
			BlockPos above = pos.above();
			if (!world.getBlockState(above).isAir()) {
				continue;
			}
			boolean farmland = state.is(Blocks.FARMLAND);
			if (!farmland && !((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && world.canSeeSky(above))) {
				continue;
			}
			// Lower is better: an existing field beats new ground, watered beats dry, near beats far.
			double score = pos.distSqr(base) + (farmland ? 0 : 1000) + (watered(pos, water) ? 0 : 2000);
			if (score < bestScore) {
				best = pos.immutable();
				bestScore = score;
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean watered(BlockPos plot, List<BlockPos> water) {
		for (BlockPos w : water) {
			if (Math.abs(w.getX() - plot.getX()) <= WATER_REACH && Math.abs(w.getZ() - plot.getZ()) <= WATER_REACH
					&& w.getY() >= plot.getY() - 1 && w.getY() <= plot.getY()) {
				return true;
			}
		}
		return false;
	}

	private static Optional<BreedPair> findBreedPair(AgentEntity self, ServerLevel world, AgentMind mind) {
		AABB box = self.getBoundingBox().inflate(ANIMAL_RADIUS);
		Map<EntityType<?>, Animal> firstOfKind = new HashMap<>();
		for (Animal animal : world.getEntitiesOfClass(Animal.class, box,
				e -> Huntable.isHuntable(e) && e.canFallInLove() && e.getAge() == 0)) {
			Optional<String> feed = feedFor(animal, mind);
			if (feed.isEmpty()) {
				continue;
			}
			Animal other = firstOfKind.putIfAbsent(animal.getType(), animal);
			if (other != null) {
				return Optional.of(new BreedPair(other, animal, feed.get()));
			}
		}
		return Optional.empty();
	}

	private static Optional<String> feedFor(Animal animal, AgentMind mind) {
		for (Item feed : FEEDS) {
			String id = ItemKinds.idOf(feed.getDefaultInstance());
			if (count(mind, id) >= 2 && animal.isFood(feed.getDefaultInstance())) {
				return Optional.of(id);
			}
		}
		return Optional.empty();
	}

	private static Optional<Item> plantable(AgentMind mind) {
		for (Item seed : CROP_OF.keySet()) {
			if (count(mind, ItemKinds.idOf(seed.getDefaultInstance())) > 0) {
				return Optional.of(seed);
			}
		}
		return Optional.empty();
	}

	private static int count(AgentMind mind, String itemId) {
		int total = 0;
		for (Possession p : mind.possessions()) {
			if (p.itemId().equals(itemId)) {
				total += p.quantity();
			}
		}
		return total;
	}

	// -- doing --------------------------------------------------------------

	/** Picks a ripe crop or berries, replanting the crop if the seed is at hand. Returns items taken. */
	public static int harvest(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, long tick, EventLog log) {
		BlockState state = world.getBlockState(pos);
		if (!isRipe(state)) {
			return 0;
		}
		self.swing(InteractionHand.MAIN_HAND);
		String what;
		if (state.is(Blocks.SWEET_BERRY_BUSH)) {
			int age = state.getValue(SweetBerryBushBlock.AGE);
			Block.popResource(world, pos, new ItemStack(Items.SWEET_BERRIES, 1 + self.getRandom().nextInt(2) + (age == 3 ? 1 : 0)));
			world.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
			what = "sweet berries";
		} else {
			CropBlock crop = (CropBlock) state.getBlock();
			what = CROP_NAME.getOrDefault(crop, "crops");
			if (!world.destroyBlock(pos, true, self)) {
				return 0;
			}
			Item seed = SEED_OF.get(crop);
			int taken = PhysicalActions.collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
			if (seed != null && mind.takeItem(ItemKinds.idOf(seed.getDefaultInstance()), 1)) {
				world.setBlock(pos, crop.getStateForAge(0), 3);
				PopulationRegistry.get(world).recordFieldChunk(ChunkPos.containing(pos).pack());
			}
			note(mind, log, tick, "I harvested " + what + ".", " harvested " + what + ".");
			return taken;
		}
		int taken = PhysicalActions.collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
		note(mind, log, tick, "I picked " + what + ".", " picked " + what + ".");
		return taken;
	}

	/** Tills {@code plot} if needed and plants a crop on it. Returns whether something was planted. */
	public static boolean plant(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos plot, long tick, EventLog log) {
		Optional<Item> seed = plantable(mind);
		BlockState ground = world.getBlockState(plot);
		BlockPos above = plot.above();
		if (seed.isEmpty() || !world.getBlockState(above).isAir()
				|| !(ground.is(Blocks.FARMLAND) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT))) {
			return false;
		}
		String seedId = ItemKinds.idOf(seed.get().getDefaultInstance());
		if (!mind.takeItem(seedId, 1)) {
			return false;
		}
		self.swing(InteractionHand.MAIN_HAND);
		boolean newField = !ground.is(Blocks.FARMLAND);
		if (newField) {
			world.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), 3);
		}
		Block crop = CROP_OF.get(seed.get());
		world.setBlock(above, ((CropBlock) crop).getStateForAge(0), 3);
		PopulationRegistry.get(world).recordFieldChunk(ChunkPos.containing(plot).pack());
		String what = ItemKinds.displayName(seedId);
		note(mind, log, tick,
				newField ? "I dug a new patch of farmland and planted " + what + "." : "I planted " + what + " in my field.",
				newField ? " dug farmland and planted " + what + "." : " planted " + what + ".");
		return true;
	}

	/**
	 * Tends the crops within a couple of blocks of {@code center}: each
	 * growing crop moves on one stage. Care is what makes a field worth
	 * coming back to; untended crops still grow, only slowly. Returns how
	 * many crops were tended.
	 */
	public static int tend(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos center, long tick, EventLog log) {
		int tended = 0;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-TEND_RADIUS, -1, -TEND_RADIUS), center.offset(TEND_RADIUS, 1, TEND_RADIUS))) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
				world.setBlock(pos, crop.getStateForAge(Math.min(crop.getMaxAge(), ageOf(crop, state) + 1)), 2);
				tended++;
			}
		}
		if (tended > 0) {
			self.swing(InteractionHand.MAIN_HAND);
			note(mind, log, tick, "I tended " + tended + " crops in my field.", " tended their field.");
		}
		return tended;
	}

	/** A crop's growth stage; works for every crop, including beetroot's shorter age property. */
	private static int ageOf(CropBlock crop, BlockState state) {
		for (int age = 0; age < crop.getMaxAge(); age++) {
			if (crop.getStateForAge(age).equals(state)) {
				return age;
			}
		}
		return crop.getMaxAge();
	}

	/** Cuts grass for the seeds it sometimes drops. Returns how many items it yielded. */
	public static int cutGrass(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos pos, long tick) {
		BlockState state = world.getBlockState(pos);
		if (!(state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))) {
			return 0;
		}
		self.swing(InteractionHand.MAIN_HAND);
		if (!world.destroyBlock(pos, true, self)) {
			return 0;
		}
		int got = PhysicalActions.collectFreshDrops(world, mind, Vec3.atCenterOf(pos), tick);
		if (got > 0) {
			mind.perceive(tick, "I found seeds in the grass.", 0.3, Set.of());
		}
		return got;
	}

	/** Feeds both animals of a pair so they breed. Returns whether it did. */
	public static boolean breed(AgentEntity self, AgentMind mind, BreedPair pair, long tick, EventLog log) {
		if (!pair.a().isAlive() || !pair.b().isAlive() || !pair.a().canFallInLove() || !pair.b().canFallInLove()
				|| self.distanceTo(pair.a()) > FEED_REACH || self.distanceTo(pair.b()) > FEED_REACH + 3) {
			return false;
		}
		if (!mind.takeItem(pair.feedId(), 2)) {
			return false;
		}
		self.swing(InteractionHand.MAIN_HAND);
		pair.a().setInLove(null);
		pair.b().setInLove(null);
		String kind = pair.a().getType().getDescription().getString().toLowerCase();
		note(mind, log, tick, "I fed two " + kind + "s so they would breed.", " fed two " + kind + "s to breed them.");
		return true;
	}

	/** Bakes bread from three wheat if there is nothing else to eat. Returns whether it did. */
	public static boolean bakeBread(AgentMind mind, long tick) {
		String wheat = ItemKinds.idOf(Items.WHEAT.getDefaultInstance());
		if (count(mind, wheat) < 3 || !mind.takeItem(wheat, 3)) {
			return false;
		}
		mind.receiveItem(tick, ItemKinds.idOf(Items.BREAD.getDefaultInstance()), 1);
		mind.perceive(tick, "I made bread from my wheat.", 0.3, Set.of());
		return true;
	}

	private static void note(AgentMind mind, EventLog log, long tick, String memory, String event) {
		mind.perceive(tick, memory, 0.3, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()), mind.identity().name() + event, List.of());
	}
}
