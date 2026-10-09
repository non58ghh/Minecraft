package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Possession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

/**
 * A furnace by the door and a hot meal. An agent with a home and eight
 * cobblestone makes a furnace and sets it up just outside; whenever it is
 * near a furnace with raw meat (or potatoes) and something to burn (coal,
 * or wood), it cooks them. Cooked food is worth two to three times as much,
 * so this is the step from scraping by to getting ahead.
 */
public final class Cooking {

	private static final Map<String, String> COOKED = Map.of(
			"minecraft:beef", "minecraft:cooked_beef",
			"minecraft:porkchop", "minecraft:cooked_porkchop",
			"minecraft:chicken", "minecraft:cooked_chicken",
			"minecraft:mutton", "minecraft:cooked_mutton",
			"minecraft:rabbit", "minecraft:cooked_rabbit",
			"minecraft:cod", "minecraft:cooked_cod",
			"minecraft:salmon", "minecraft:cooked_salmon",
			"minecraft:potato", "minecraft:baked_potato");
	private static final String FURNACE = "minecraft:furnace";
	private static final String COBBLESTONE = "minecraft:cobblestone";
	private static final int FURNACE_COST = 8;
	/** How close to a furnace it has to be to use it. */
	private static final int REACH = 6;
	/** A furnace this close to home counts as its own. */
	private static final int HOME_FURNACE_RADIUS = 6;
	/** Items one coal cooks, as in vanilla; wood cooks one and a half, rounded down to one here. */
	private static final int PER_COAL = 8;

	private Cooking() {
	}

	/** Raw food that would be much better cooked. */
	public static boolean isCookable(String itemId) {
		return COOKED.containsKey(itemId);
	}

	/**
	 * Called now and then: sets up a furnace at home if it has none, and
	 * cooks whatever raw food it carries when a furnace is within reach.
	 */
	public static void tick(AgentEntity self, ServerLevel world, AgentMind mind, long tick, EventLog log) {
		Optional<Home> home = mind.home();
		BlockPos here = self.blockPosition();
		if (home.isPresent()) {
			BlockPos origin = new BlockPos(home.get().x(), home.get().y(), home.get().z());
			if (here.distSqr(origin) <= 64 && findFurnace(world, origin, HOME_FURNACE_RADIUS).isEmpty()) {
				setUpFurnace(self, world, mind, origin, home.get().design(), tick, log);
			}
		}
		if (mind.possessions().stream().anyMatch(p -> isCookable(p.itemId()))
				&& findFurnace(world, here, REACH).isPresent()) {
			cook(self, mind, tick, log);
		}
	}

	private static void setUpFurnace(AgentEntity self, ServerLevel world, AgentMind mind, BlockPos origin, Design design,
			long tick, EventLog log) {
		if (mind.countOf(FURNACE) == 0) {
			if (mind.countOf(COBBLESTONE) < FURNACE_COST || !mind.takeItem(COBBLESTONE, FURNACE_COST)) {
				return;
			}
			mind.receiveItem(tick, FURNACE, 1);
		}
		// Just outside the door, to one side, on firm ground.
		Design.Cell entrance = design.entrance();
		BlockPos front = origin.offset(entrance.dx(), entrance.dy(), entrance.dz());
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos spot = front.relative(side);
			if (world.getBlockState(spot).isAir() && world.getBlockState(spot.below()).isFaceSturdy(world, spot.below(), Direction.UP)
					&& !isInside(design, origin, spot)) {
				if (!mind.takeItem(FURNACE, 1)) {
					return;
				}
				world.setBlock(spot, Blocks.FURNACE.defaultBlockState(), 3);
				self.swing(InteractionHand.MAIN_HAND);
				mind.perceive(tick, "I made a furnace and set it up by my door.", 0.5, Set.of());
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + " set up a furnace by their home.", List.of());
				return;
			}
		}
	}

	private static boolean isInside(Design design, BlockPos origin, BlockPos spot) {
		for (Design.Cell cell : design.footprintVolume()) {
			if (origin.offset(cell.dx(), cell.dy(), cell.dz()).equals(spot)) {
				return true;
			}
		}
		return false;
	}

	private static Optional<BlockPos> findFurnace(ServerLevel world, BlockPos center, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
			if (world.getBlockState(pos).is(Blocks.FURNACE)) {
				return Optional.of(pos.immutable());
			}
		}
		return Optional.empty();
	}

	private static void cook(AgentEntity self, AgentMind mind, long tick, EventLog log) {
		int cooked = 0;
		String lastName = "";
		for (Possession p : List.copyOf(mind.possessions())) {
			if (!isCookable(p.itemId())) {
				continue;
			}
			int n = p.quantity();
			int fuelled = burnFuelFor(mind, n);
			if (fuelled <= 0 || !mind.takeItem(p.itemId(), fuelled)) {
				break;
			}
			mind.receiveItem(tick, COOKED.get(p.itemId()), fuelled);
			cooked += fuelled;
			lastName = ItemKinds.displayName(COOKED.get(p.itemId()));
		}
		if (cooked > 0) {
			self.swing(InteractionHand.MAIN_HAND);
			String what = cooked == 1 ? "some " + lastName : cooked + " pieces of food";
			mind.perceive(tick, "I cooked " + what + " at the furnace.", 0.35, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + " cooked " + what + ".", List.of());
		}
	}

	/** Uses up fuel for up to {@code wanted} items; returns how many it can cook. */
	private static int burnFuelFor(AgentMind mind, int wanted) {
		for (String coal : new String[] {"minecraft:coal", "minecraft:charcoal"}) {
			if (mind.countOf(coal) > 0) {
				int coals = Math.min(mind.countOf(coal), (wanted + PER_COAL - 1) / PER_COAL);
				mind.takeItem(coal, coals);
				return Math.min(wanted, coals * PER_COAL);
			}
		}
		// Wood burns too: a plank (or a split log) per item.
		int done = 0;
		for (Possession p : List.copyOf(mind.possessions())) {
			if (done >= wanted) {
				break;
			}
			if (p.itemId().endsWith("_planks") || ItemKinds.isLog(p.itemId())) {
				int take = Math.min(p.quantity(), wanted - done);
				if (mind.takeItem(p.itemId(), take)) {
					done += take;
				}
			}
		}
		return done;
	}
}
