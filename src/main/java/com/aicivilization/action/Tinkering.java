package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Invention;
import com.aicivilization.mind.KnownInvention;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Building an invention and trying it, with the game's own redstone as the
 * judge. The device goes up on clear ground near the agent's home, or at its
 * door; it then pulls the lever, presses the button or stands on the plate
 * (or, for a device meant to run by itself, just watches it a while), and
 * looks at what changed. If the part it meant to respond did, the invention
 * works; either way it remembers what it saw. A device that works is left
 * standing for anyone to see and use (see {@link Devices}).
 */
public final class Tinkering {

	/** Ticks to let a new device settle before trying it, and to wait for it to respond (repeaters take time). */
	static final int SETTLE_TICKS = 4, WATCH_TICKS = 30;
	/** A device that runs by itself is watched this long. */
	static final int WATCH_ALONE_TICKS = 200;
	/** How far from home a site is looked for. */
	static final int SITE_MIN = 5, SITE_MAX = 14;
	/** Block state that says something happened, and how to say it. */
	private static final Map<String, String[]> SAY = Map.of(
			"open", new String[] {"opened", "closed"},
			"extended", new String[] {"pushed out", "pulled back"},
			"lit", new String[] {"lit up", "went dark"},
			"triggered", new String[] {"fired", "stopped"},
			"enabled", new String[] {"woke", "stopped"},
			"powered", new String[] {"was powered", "lost power"});

	/** Devices being tried right now, ticked by the server. */
	private static final List<Trial> TRIALS = new ArrayList<>();

	private Tinkering() {
	}

	/** What a trial found. {@code changes} is every part that did something, in plain words. */
	public record Outcome(boolean works, List<String> changes) {
		public String observed() {
			return changes.isEmpty() ? "nothing happened" : String.join(", ", changes);
		}
	}

	/**
	 * Where a device stands: {@code base} is its 0,0,0, and {@code out} which
	 * way its z runs (south near home; away from the door at the door), with x
	 * to the right of someone looking that way.
	 */
	public record Site(BlockPos base, Direction out) {
		public BlockPos at(Invention.Cell c) {
			Direction right = out.getClockWise();
			return base.offset(right.getStepX() * c.x() + out.getStepX() * c.z(), c.y(),
					right.getStepZ() * c.x() + out.getStepZ() * c.z());
		}

		/** A facing as the inventor wrote it, in the world: "away", "toward_door", "left" and "right" are relative to {@link #out}. */
		String facing(String written) {
			Direction right = out.getClockWise();
			return switch (written) {
				case "away", "out" -> out.getSerializedName();
				case "toward_door", "toward", "in", "back" -> out.getOpposite().getSerializedName();
				case "right" -> right.getSerializedName();
				case "left" -> right.getOpposite().getSerializedName();
				default -> written;
			};
		}
	}

	/** Items it still needs to build this, as item id to count; empty when it has everything. */
	public static Map<String, Integer> missing(AgentMind mind, Invention invention) {
		Map<String, Integer> out = new LinkedHashMap<>();
		invention.materials().forEach((item, n) -> {
			int short_ = n - mind.countOf(item);
			if (short_ > 0) {
				out.put(item, short_);
			}
		});
		return out;
	}

	/** Where to build it for this home: at its door, or on clear ground nearby. Empty if there's no room. */
	public static Optional<Site> siteFor(ServerLevel world, BlockPos origin, Design design, Invention invention) {
		Set<BlockPos> house = new HashSet<>();
		for (Design.Cell c : design.footprintVolume()) {
			house.add(new BlockPos(origin.getX() + c.dx(), 0, origin.getZ() + c.dz()));
		}
		Design.Cell front = design.entrance();
		BlockPos doorstep = origin.offset(front.dx(), front.dy(), front.dz());
		if (invention.anchor() == Invention.Anchor.DOOR) {
			BlockPos doorway = null;
			for (Design.Cell c : design.doorway()) {
				if (c.dy() == 0) {
					doorway = origin.offset(c.dx(), c.dy(), c.dz());
				}
			}
			if (doorway == null) {
				return Optional.empty();
			}
			Direction out = Direction.getApproximateNearest(doorstep.getX() - doorway.getX(), 0, doorstep.getZ() - doorway.getZ());
			Site site = new Site(doorstep, out.getAxis() == Direction.Axis.Y ? Direction.SOUTH : out);
			return fits(world, site, invention, house, doorstep, true) ? Optional.of(site) : Optional.empty();
		}
		for (int r = SITE_MIN; r <= SITE_MAX; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = origin.getX() + dx, z = origin.getZ() + dz;
					Site site = new Site(new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z), Direction.SOUTH);
					if (Math.abs(site.base().getY() - origin.getY()) <= 3 && fits(world, site, invention, house, doorstep, false)) {
						return Optional.of(site);
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Every cell it needs is empty, ground-level parts stand on something
	 * firm, and nothing goes in the house. At the door, the doorstep may only
	 * take what can be walked over; elsewhere the doorstep is left clear.
	 */
	private static boolean fits(ServerLevel world, Site site, Invention invention, Set<BlockPos> house, BlockPos doorstep, boolean atDoor) {
		for (Invention.Part p : invention.parts()) {
			BlockPos at = site.at(p.at());
			if (house.contains(new BlockPos(at.getX(), 0, at.getZ()))) {
				return false;
			}
			if (at.getX() == doorstep.getX() && at.getZ() == doorstep.getZ()) {
				Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(p.block())).orElse(null);
				if (!atDoor || block == null || !block.defaultBlockState().getCollisionShape(world, at).isEmpty()) {
					return false;
				}
			}
			BlockState here = world.getBlockState(at);
			if (!(here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty())) {
				return false;
			}
			if (p.at().y() == 0 && !world.getBlockState(at.below()).isFaceSturdy(world, at.below(), Direction.UP)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Builds it on {@code site} from what the agent carries (or for nothing,
	 * when {@code free}) and starts trying it. Returns why not, or empty if
	 * it's under way; the outcome is remembered and logged when the trial ends.
	 */
	public static Optional<String> buildAndTry(AgentEntity self, ServerLevel world, AgentMind mind, Invention invention,
			Site site, boolean free, long tick, EventLog log) {
		if (!free && !missing(mind, invention).isEmpty()) {
			return Optional.of("not enough materials");
		}
		// Work out every block first: a part the game has no such state for means it can't be built at all.
		Map<BlockPos, BlockState> placing = new LinkedHashMap<>();
		List<Invention.Part> order = new ArrayList<>(invention.parts());
		order.sort(Comparator.comparingInt((Invention.Part p) -> p.at().y())
				.thenComparing(p -> Invention.TRIGGERS.contains(p.block()) || p.block().equals("minecraft:redstone_wire") ? 1 : 0));
		for (Invention.Part p : order) {
			Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(p.block())).orElse(null);
			if (block == null) {
				return Optional.of("there's no such block as " + p.block());
			}
			BlockState state = block.defaultBlockState();
			for (Map.Entry<String, String> e : p.state().entrySet()) {
				Property<?> prop = block.getStateDefinition().getProperty(e.getKey());
				String value = e.getKey().equals("facing") ? site.facing(e.getValue()) : e.getValue();
				BlockState next = prop == null ? null : with(state, prop, value);
				if (next == null) {
					return Optional.of(ItemKinds.displayName(p.block()) + " can't be \"" + e.getKey() + ": " + e.getValue() + "\"");
				}
				state = next;
			}
			BlockPos at = site.at(p.at());
			if (block instanceof DoorBlock) {
				placing.put(at, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
				placing.put(at.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
			} else {
				placing.put(at, state);
			}
		}
		if (!free) {
			invention.materials().forEach(mind::takeItem);
		}
		int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
		placing.forEach((at, state) -> world.setBlock(at, state, quiet));
		// Then let each part settle against its neighbours: wires join up, a button with nothing behind it falls off.
		for (BlockPos at : placing.keySet()) {
			BlockState settled = Block.updateFromNeighbourShapes(world.getBlockState(at), world, at);
			world.setBlock(at, settled, Block.UPDATE_ALL);
		}
		for (BlockPos at : placing.keySet()) {
			world.updateNeighborsAt(at, world.getBlockState(at).getBlock());
		}
		self.swing(InteractionHand.MAIN_HAND);
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " built a device of their own, " + invention.name() + ", to " + lower(invention.purpose()) + ".",
				List.of());
		TRIALS.add(new Trial(self.getUUID(), world, invention, site, tick));
		return Optional.empty();
	}

	/** Every tick: moves each trial along. */
	public static void tickTrials(long tick) {
		for (Iterator<Trial> it = TRIALS.iterator(); it.hasNext();) {
			if (it.next().tick(tick)) {
				it.remove();
			}
		}
	}

	/** Whether this agent is in the middle of trying a device (and should stay put). */
	public static boolean trying(UUID agent) {
		return TRIALS.stream().anyMatch(t -> t.agent.equals(agent));
	}

	private static final class Trial {
		final UUID agent;
		final ServerLevel world;
		final Invention invention;
		final Site site;
		final long started;
		final List<BlockPos> cells = new ArrayList<>();
		Map<BlockPos, BlockState> before;
		long triggeredAt = -1;
		final List<String> seen = new ArrayList<>();
		boolean outputChanged;

		Trial(UUID agent, ServerLevel world, Invention invention, Site site, long started) {
			this.agent = agent;
			this.world = world;
			this.invention = invention;
			this.site = site;
			this.started = started;
			for (Invention.Part p : invention.parts()) {
				cells.add(site.at(p.at()));
			}
		}

		/** Returns true when the trial is over. */
		boolean tick(long tick) {
			if (!(world.getEntity(agent) instanceof AgentEntity self) || self.mind() == null || !self.isAlive()) {
				return true;
			}
			BlockPos trigger = invention.trigger() == null ? null : site.at(invention.trigger());
			if (triggeredAt < 0) {
				if (tick - started < SETTLE_TICKS) {
					return false;
				}
				before = snapshot();
				triggeredAt = tick;
				if (trigger != null && !pull(self, trigger)) {
					return finish(self, tick, new Outcome(false, List.of("the " + name(trigger) + " wasn't there to use")));
				}
				return false;
			}
			BlockState t = trigger == null ? null : world.getBlockState(trigger);
			if (t != null && stoodOn(t)) {
				// Stays on the plate or in the tripwire for the whole trial, the way you'd test one.
				self.getNavigation().stop();
				self.teleportTo(trigger.getX() + 0.5, trigger.getY(), trigger.getZ() + 0.5);
			}
			long watch = invention.selfRunning() ? WATCH_ALONE_TICKS : WATCH_TICKS;
			if ((tick - triggeredAt) % 10 == 0 || tick - triggeredAt >= watch) {
				// A device that runs by itself may come and go: look now and then, not only at the end.
				Outcome look = judge(before, snapshot(), site.at(invention.output()), trigger);
				outputChanged |= look.works();
				look.changes().stream().filter(c -> !seen.contains(c)).forEach(seen::add);
			}
			if (tick - triggeredAt < watch) {
				return false;
			}
			if (t != null && t.getBlock() instanceof LeverBlock lever && t.getValue(LeverBlock.POWERED)) {
				lever.pull(t, world, trigger, null); // leaves it as it found it
			}
			return finish(self, tick, new Outcome(outputChanged, List.copyOf(seen)));
		}

		boolean stoodOn(BlockState t) {
			String id = BuiltInRegistries.BLOCK.getKey(t.getBlock()).toString();
			return Invention.STOOD_ON.contains(id);
		}

		/** Sets it off. Returns whether there was something there to set off. */
		boolean pull(AgentEntity self, BlockPos trigger) {
			BlockState t = world.getBlockState(trigger);
			self.getLookControl().setLookAt(trigger.getX() + 0.5, trigger.getY() + 0.5, trigger.getZ() + 0.5);
			self.swing(InteractionHand.MAIN_HAND);
			if (t.getBlock() instanceof LeverBlock lever) {
				lever.pull(t, world, trigger, null);
			} else if (t.getBlock() instanceof ButtonBlock button) {
				button.press(t, world, trigger, null);
			} else if (stoodOn(t)) {
				self.getNavigation().stop();
				self.teleportTo(trigger.getX() + 0.5, trigger.getY(), trigger.getZ() + 0.5);
			} else {
				return false;
			}
			return true;
		}

		Map<BlockPos, BlockState> snapshot() {
			Map<BlockPos, BlockState> out = new LinkedHashMap<>();
			for (BlockPos c : cells) {
				out.put(c, world.getBlockState(c));
				out.put(c.above(), world.getBlockState(c.above()));
			}
			return out;
		}

		boolean finish(AgentEntity self, long tick, Outcome outcome) {
			AgentMind mind = self.mind();
			EventLog log = EventLog.get(world);
			mind.rememberInvention(new KnownInvention(invention, "invented", mind.identity().name(), mind.identity().id(), tick,
					outcome.works(), outcome.observed()));
			if (outcome.works()) {
				Devices.get(world).add(invention, site, mind.identity().id(), mind.identity().name(), tick);
				mind.perceive(tick, "I built " + invention.name() + " to " + lower(invention.purpose()) + ", and it worked: "
						+ outcome.observed() + ".", 0.8, Set.of());
				log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()), mind.identity().name() + " invented "
						+ invention.name() + ", to " + lower(invention.purpose()) + ": " + outcome.observed() + ".", List.of());
			} else {
				mind.perceive(tick, "I built " + invention.name() + " to " + lower(invention.purpose()) + ", but it didn't work: "
						+ outcome.observed() + ".", 0.55, Set.of());
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()), mind.identity().name() + " tried out "
						+ invention.name() + ", but it didn't do what they meant: " + outcome.observed() + ".", List.of());
			}
			return true;
		}

		private String name(BlockPos pos) {
			return ItemKinds.displayName(BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString());
		}
	}

	/**
	 * What changed between two looks at the device, in plain words, and
	 * whether the output was among it. The trigger's own change (a lever
	 * flipped) and wires carrying power don't count as the device doing
	 * anything.
	 */
	static Outcome judge(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after, BlockPos output, BlockPos trigger) {
		List<String> changes = new ArrayList<>();
		boolean outputChanged = false;
		for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
			BlockPos pos = e.getKey();
			BlockState was = e.getValue();
			BlockState now = after.get(pos);
			if (now == null || was.equals(now) || pos.equals(trigger)) {
				continue;
			}
			if (pos.equals(output)) {
				outputChanged = true;
			}
			String said = describe(was, now);
			if (said != null && !changes.contains(said)) {
				changes.add(said);
			}
		}
		return new Outcome(outputChanged, changes);
	}

	/** "the oak door opened", "a piston head appeared"; null for a wire changing power, which nobody notices. */
	static String describe(BlockState was, BlockState now) {
		String wasName = ItemKinds.displayName(BuiltInRegistries.BLOCK.getKey(was.getBlock()).toString());
		String nowName = ItemKinds.displayName(BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString());
		if (was.getBlock() != now.getBlock()) {
			if (now.isAir()) {
				return "the " + wasName + " moved away";
			}
			if (was.isAir()) {
				return "a " + nowName + " appeared";
			}
			if (now.is(Blocks.PISTON_HEAD) || now.is(Blocks.MOVING_PISTON)) {
				return "the " + wasName + " was pushed along";
			}
			return "the " + wasName + " became a " + nowName;
		}
		if (was.is(Blocks.REDSTONE_WIRE)) {
			return null;
		}
		for (Property<?> prop : was.getProperties()) {
			String[] words = SAY.get(prop.getName());
			if (words != null && !was.getValue(prop).equals(now.getValue(prop)) && now.getValue(prop) instanceof Boolean on) {
				return "the " + wasName + " " + (was.is(Blocks.NOTE_BLOCK) && on ? "sounded" : on ? words[0] : words[1]);
			}
		}
		return "the " + wasName + " changed";
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> prop, String value) {
		return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(null);
	}

	static String lower(String purpose) {
		String p = purpose.strip();
		if (p.toLowerCase(Locale.ROOT).startsWith("to ")) {
			p = p.substring(3);
		}
		return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1).replaceAll("[.!]+$", "");
	}

	/** Home origin from a home, for callers outside the behaviour package. */
	public static BlockPos originOf(Home home) {
		return new BlockPos(home.x(), home.y(), home.z());
	}
}
