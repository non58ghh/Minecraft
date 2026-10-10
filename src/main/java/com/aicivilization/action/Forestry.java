package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Places;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.RecipeBook;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Saplings and what agents come to know about them. Nobody starts out
 * knowing that a sapling grows into a tree ({@link RecipeBook#REPLANTING}):
 * an agent learns it by seeing a sapling it noticed (planted by anyone,
 * a player included) later standing as a tree. Curious agents, and those
 * who have heard of it, may pick saplings up and set one in the ground to
 * see what happens, which gives them something to watch. Once it knows,
 * an agent keeps saplings and plants one where each tree it fells stood.
 */
public final class Forestry {

	/** How far around it notices a sapling in the ground. */
	private static final int SAPLING_SIGHT = 16;
	/** It checks on a watched sapling when it passes this close. */
	private static final int CHECK_DISTANCE = 12;
	private static final double PICKUP_RADIUS = 3.0;
	/** Saplings worth carrying. */
	private static final int MAX_SAPLINGS = 6;
	/** Only the quite curious try planting something nobody told them about. */
	private static final double CURIOUS = 0.6;
	/** Chance per decision of setting a sapling in the ground, for the curious (times curiosity) and for those who've heard of it. */
	private static final double EXPERIMENT_CHANCE_CURIOUS = 0.03;
	private static final double EXPERIMENT_CHANCE_HEARD = 0.15;

	private Forestry() {
	}

	/** Has a reason to keep a sapling: knows, has heard, or is curious enough to wonder. */
	public static boolean wantsSaplings(AgentMind mind) {
		RecipeBook book = mind.recipeBook();
		return book.knowsPractice(RecipeBook.REPLANTING) || book.heardOfPractice(RecipeBook.REPLANTING)
				|| mind.personality().curiosity() > CURIOUS;
	}

	/**
	 * Looks around once per decision: notices saplings in the ground, checks
	 * on ones it has been watching (learning, if one has become a tree), and
	 * picks up saplings lying close by if it has a reason to keep them.
	 */
	public static void look(AgentEntity self, ServerLevel world, AgentMind mind, long tick, EventLog log) {
		Places places = mind.places();
		BlockPos here = self.blockPosition();
		boolean knows = mind.recipeBook().knowsPractice(RecipeBook.REPLANTING);
		if (!knows) {
			for (BlockPos pos : BlockPos.betweenClosed(here.offset(-SAPLING_SIGHT, -3, -SAPLING_SIGHT),
					here.offset(SAPLING_SIGHT, 3, SAPLING_SIGHT))) {
				if (isSapling(world.getBlockState(pos))
						&& places.of(Places.Kind.SAPLING, tick).stream().noneMatch(w -> w.x() == pos.getX() && w.y() == pos.getY() && w.z() == pos.getZ())) {
					places.note(Places.Kind.SAPLING, pos.getX(), pos.getY(), pos.getZ(), tick);
				}
			}
			long checkSq = (long) CHECK_DISTANCE * CHECK_DISTANCE;
			for (Places.Place w : places.of(Places.Kind.SAPLING, tick)) {
				BlockPos at = new BlockPos(w.x(), w.y(), w.z());
				if (at.distSqr(here) > checkSq) {
					continue;
				}
				BlockState now = world.getBlockState(at);
				if (now.is(BlockTags.LOGS)) {
					learnFromGrowth(mind, w, tick, log);
					places.forgetAll(Places.Kind.SAPLING);
					break;
				}
				if (!isSapling(now)) {
					places.forget(Places.Kind.SAPLING, w.x(), w.y(), w.z(), 0);
				}
			}
		}
		if (wantsSaplings(mind) && saplingsCarried(mind) < MAX_SAPLINGS) {
			AABB box = new AABB(self.position(), self.position()).inflate(PICKUP_RADIUS);
			for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, box, e -> e.getItem().is(ItemTags.SAPLINGS))) {
				int take = Math.min(drop.getItem().getCount(), MAX_SAPLINGS - saplingsCarried(mind));
				if (take <= 0) {
					break;
				}
				mind.receiveItem(tick, ItemKinds.idOf(drop.getItem()), take);
				if (take == drop.getItem().getCount()) {
					drop.discard();
				} else {
					drop.getItem().shrink(take);
				}
			}
		}
	}

	private static void learnFromGrowth(AgentMind mind, Places.Place w, long tick, EventLog log) {
		boolean own = w.mine();
		mind.recipeBook().learnPractice(RecipeBook.REPLANTING, new RecipeBook.Learned(own ? "made" : "saw", "", null, tick));
		mind.perceive(tick, own
				? "The sapling I set in the ground has grown into a tree. Saplings grow into trees if you plant them."
				: "A sapling I'd seen in the ground has grown into a tree. Saplings grow into trees if you plant them.",
				0.75, Set.of());
		log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
				mind.identity().name() + " learned that saplings grow into trees, from seeing "
						+ (own ? "one they'd planted" : "one") + " grow.", List.of());
	}

	/**
	 * Without knowing what saplings do: the curious, or those who've heard,
	 * now and then set one in the ground nearby to see, and keep an eye on it.
	 * {@code roll} is a uniform draw in [0, 1).
	 */
	public static void maybeExperiment(AgentEntity self, ServerLevel world, AgentMind mind, long tick, EventLog log, double roll) {
		RecipeBook book = mind.recipeBook();
		if (book.knowsPractice(RecipeBook.REPLANTING)) {
			return;
		}
		double chance = book.heardOfPractice(RecipeBook.REPLANTING) ? EXPERIMENT_CHANCE_HEARD
				: mind.personality().curiosity() > CURIOUS ? EXPERIMENT_CHANCE_CURIOUS * mind.personality().curiosity() : 0;
		if (roll >= chance) {
			return;
		}
		Optional<String> sapling = carriedSapling(mind);
		if (sapling.isEmpty()) {
			return;
		}
		BlockPos here = self.blockPosition();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos spot = here.offset(dx, 0, dz);
				if (plant(world, mind, sapling.get(), spot)) {
					mind.places().note(Places.Kind.SAPLING, spot.getX(), spot.getY(), spot.getZ(), tick, null, true);
					boolean heard = book.heardOfPractice(RecipeBook.REPLANTING);
					mind.perceive(tick, heard ? "I set a sapling in the ground, to see if it really grows into a tree."
							: "I set a sapling in the ground, to see what becomes of it.", 0.4, Set.of());
					log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
							mind.identity().name() + (heard ? " set a sapling in the ground to see if it grows, as they'd heard."
									: " set a sapling in the ground to see what becomes of it."), List.of());
					return;
				}
			}
		}
	}

	/** Knowing saplings grow: one goes in where the tree it just felled stood. */
	public static void replant(ServerLevel world, AgentMind mind, BlockPos stump, long tick, EventLog log) {
		if (!mind.recipeBook().knowsPractice(RecipeBook.REPLANTING)) {
			return;
		}
		carriedSapling(mind).ifPresent(sapling -> {
			if (plant(world, mind, sapling, stump)) {
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + " set a sapling where the tree stood.", List.of());
			}
		});
	}

	/** Puts {@code saplingId} into the ground at {@code spot} if it can grow there, taking it from the pack. */
	private static boolean plant(ServerLevel world, AgentMind mind, String saplingId, BlockPos spot) {
		Identifier key = Identifier.tryParse(saplingId);
		Optional<Item> item = key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
		if (item.isEmpty() || !(item.get() instanceof BlockItem blockItem)) {
			return false;
		}
		BlockState sapling = blockItem.getBlock().defaultBlockState();
		if (!world.getBlockState(spot).isAir() || !sapling.canSurvive(world, spot) || !mind.takeItem(saplingId, 1)) {
			return false;
		}
		world.setBlockAndUpdate(spot, sapling);
		return true;
	}

	/** A sapling in the ground (there's no block tag for them; their item form is tagged). */
	private static boolean isSapling(BlockState state) {
		return !state.isAir() && state.getBlock().asItem().getDefaultInstance().is(ItemTags.SAPLINGS);
	}

	private static Optional<String> carriedSapling(AgentMind mind) {
		for (Possession p : mind.possessions()) {
			if (p.quantity() > 0 && p.itemId().endsWith("_sapling")) {
				return Optional.of(p.itemId());
			}
		}
		return Optional.empty();
	}

	private static int saplingsCarried(AgentMind mind) {
		int n = 0;
		for (Possession p : mind.possessions()) {
			if (p.itemId().endsWith("_sapling")) {
				n += p.quantity();
			}
		}
		return n;
	}

}
