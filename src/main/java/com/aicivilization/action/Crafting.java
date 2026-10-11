package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Possession;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Making and using tools, the way a player does: logs into planks, planks
 * into sticks, and planks or cobblestone plus sticks into an axe, pickaxe,
 * sword or hoe. Agents craft from their pack when they have the materials
 * (keeping enough wood back to build a home if they don't have one yet),
 * hold the right tool for the job, and tools wear out and break.
 *
 * <p>What each tool is for: an axe brings down more of a tree per swing, a
 * pickaxe digs stone (fast, and keeps the cobblestone), a sword hits harder
 * when hunting, a hoe makes tending crops go further.
 */
public final class Crafting {

	public enum Tool {
		AXE("axe", 3), PICKAXE("pickaxe", 3), SWORD("sword", 2), HOE("hoe", 2);

		final String suffix;
		final int heads;

		Tool(String suffix, int heads) {
			this.suffix = suffix;
			this.heads = heads;
		}

		int sticks() {
			return this == SWORD ? 1 : 2;
		}
	}

	private static final String STICK = "minecraft:stick";
	private static final String COBBLESTONE = "minecraft:cobblestone";
	private static final String WOODEN = "minecraft:wooden_";
	private static final String STONE = "minecraft:stone_";
	private static final String IRON = "minecraft:iron_";
	private static final String DIAMOND = "minecraft:diamond_";
	/** Uses before a tool breaks, as in vanilla. */
	private static final int WOODEN_USES = 59;
	private static final int STONE_USES = 131;
	private static final int IRON_USES = 250;
	private static final int DIAMOND_USES = 1561;
	/** Planks kept back for a first home, so tools don't eat the walls. */
	private static final int HOMELESS_WOOD_RESERVE = 24;

	private Crafting() {
	}

	/** The best tool of this kind the agent carries, if any: diamond, iron, then stone, then wood. */
	public static Optional<String> best(AgentMind mind, Tool tool) {
		if (mind.countOf(DIAMOND + tool.suffix) > 0) {
			return Optional.of(DIAMOND + tool.suffix);
		}
		if (mind.countOf(IRON + tool.suffix) > 0) {
			return Optional.of(IRON + tool.suffix);
		}
		if (mind.countOf(STONE + tool.suffix) > 0) {
			return Optional.of(STONE + tool.suffix);
		}
		if (mind.countOf(WOODEN + tool.suffix) > 0) {
			return Optional.of(WOODEN + tool.suffix);
		}
		return Optional.empty();
	}

	/** Iron or better: good enough for gold, redstone and diamond. */
	public static boolean isIron(String toolId) {
		return toolId.startsWith(IRON) || isDiamond(toolId);
	}

	/** Diamond: the only thing that cuts obsidian. */
	public static boolean isDiamond(String toolId) {
		return toolId.startsWith(DIAMOND);
	}

	/** Stone or better: good enough for iron ore. */
	public static boolean isStone(String toolId) {
		return toolId.startsWith(STONE) || isIron(toolId);
	}

	/**
	 * Makes at most one thing the agent is missing: a tool it has none of,
	 * or a stone one to replace a wooden one once it has cobblestone.
	 * Returns whether it made something.
	 */
	public static boolean craftWhatsNeeded(AgentMind mind, boolean hasHome, long tick, EventLog log) {
		for (Tool tool : Tool.values()) {
			boolean hasStone = mind.countOf(STONE + tool.suffix) > 0;
			boolean hasWooden = mind.countOf(WOODEN + tool.suffix) > 0;
			if (!hasStone && mind.countOf(COBBLESTONE) >= tool.heads && ensureSticks(mind, tool.sticks(), hasHome, tick)
					&& mind.takeItem(COBBLESTONE, tool.heads) && mind.takeItem(STICK, tool.sticks())) {
				mind.receiveItem(tick, STONE + tool.suffix, 1);
				if (hasWooden) {
					mind.takeItem(WOODEN + tool.suffix, 1);
				}
				note(mind, log, tick, "stone " + tool.suffix);
				return true;
			}
			if (!hasStone && !hasWooden && spareWood(mind, hasHome) >= tool.heads + 2
					&& ensureSticks(mind, tool.sticks(), hasHome, tick) && takePlanks(mind, tool.heads, tick)
					&& mind.takeItem(STICK, tool.sticks())) {
				mind.receiveItem(tick, WOODEN + tool.suffix, 1);
				note(mind, log, tick, "wooden " + tool.suffix);
				return true;
			}
		}
		return false;
	}

	public static final String DOOR = "minecraft:oak_door";
	public static final String TORCH = "minecraft:torch";
	/** A coal (or charcoal) and a stick make four torches. */
	private static final int TORCHES_MADE = 4;

	/** Wood it can spare, counting a home as reason to keep less back. */
	public static int spareWoodFor(AgentMind mind) {
		return spareWood(mind, true);
	}

	/** Uses up planks (splitting logs as needed); returns whether it had enough. */
	public static boolean planks(AgentMind mind, int needed, long tick) {
		return takePlanks(mind, needed, tick);
	}

	/** Makes torches from a coal or charcoal and a stick, when it carries few. Returns whether it did. */
	public static boolean craftTorches(AgentMind mind, boolean hasHome, long tick, EventLog log) {
		if (mind.countOf(TORCH) >= TORCHES_MADE) {
			return false;
		}
		String fuel = mind.countOf("minecraft:coal") > 0 ? "minecraft:coal"
				: mind.countOf("minecraft:charcoal") > 0 ? "minecraft:charcoal" : null;
		if (fuel == null || !ensureSticks(mind, 1, hasHome, tick) || !mind.takeItem(fuel, 1) || !mind.takeItem(STICK, 1)) {
			return false;
		}
		mind.receiveItem(tick, TORCH, TORCHES_MADE);
		note(mind, log, tick, "some torches");
		return true;
	}
	/** Six planks make three doors. */
	private static final int DOOR_PLANKS = 6, DOORS_MADE = 3;

	/** Makes doors from its planks (or logs) when it has a home without one and none in its pack. Returns whether it did. */
	public static boolean craftDoor(AgentMind mind, long tick, EventLog log) {
		if (mind.countOf(DOOR) > 0 || spareWood(mind, true) < DOOR_PLANKS || !takePlanks(mind, DOOR_PLANKS, tick)) {
			return false;
		}
		mind.receiveItem(tick, DOOR, DOORS_MADE);
		note(mind, log, tick, "door");
		return true;
	}

	/** Three wool of one colour and three planks make a bed. */
	static final int BED_WOOL = 3, BED_PLANKS = 3;

	/** The colour of wool it has enough of for a bed ("white"), or null. */
	public static String bedWool(AgentMind mind) {
		for (com.aicivilization.mind.Possession p : mind.possessions()) {
			String id = p.itemId();
			if (id.startsWith("minecraft:") && id.endsWith("_wool") && p.quantity() >= BED_WOOL) {
				return id.substring("minecraft:".length(), id.length() - "_wool".length());
			}
		}
		return null;
	}

	/** Makes a bed from wool of one colour and planks. Returns the bed's item id, or null if it couldn't. */
	public static String craftBed(AgentMind mind, long tick, EventLog log) {
		String colour = bedWool(mind);
		if (colour == null || spareWood(mind, true) < BED_PLANKS || !takePlanks(mind, BED_PLANKS, tick)) {
			return null;
		}
		if (!mind.takeItem("minecraft:" + colour + "_wool", BED_WOOL)) {
			return null;
		}
		String bed = "minecraft:" + colour + "_bed";
		mind.receiveItem(tick, bed, 1);
		note(mind, log, tick, colour.replace('_', ' ') + " bed");
		return bed;
	}

	/**
	 * Holds the best tool of this kind in hand (or nothing) so it counts in a
	 * fight and others can see what the agent is doing.
	 */
	public static void hold(AgentEntity self, AgentMind mind, Tool tool) {
		Optional<String> best = tool == null ? Optional.empty() : best(mind, tool);
		ItemStack stack = best.map(id -> Enchanting.stackFor(self.level(), mind, id)).orElse(ItemStack.EMPTY);
		if (!ItemStack.isSameItemSameComponents(stack, self.getMainHandItem())) {
			self.setItemInHand(InteractionHand.MAIN_HAND, stack);
		}
	}

	/** One use of a tool: it may break, like a vanilla tool running out of durability. */
	public static void wear(AgentEntity self, AgentMind mind, String toolId, long tick, EventLog log) {
		RandomSource random = self.getRandom();
		int uses = isDiamond(toolId) ? DIAMOND_USES : toolId.startsWith(IRON) ? IRON_USES : toolId.startsWith(STONE) ? STONE_USES : WOODEN_USES;
		if (random.nextInt(uses) == 0 && !Enchanting.spared(mind, toolId, random) && mind.takeItem(toolId, 1)) {
			String name = ItemKinds.displayName(toolId);
			self.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			mind.perceive(tick, "My " + name + " broke.", 0.3, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + "'s " + name + " broke.", List.of());
		}
	}

	/** Planks on hand plus four for every log, minus what a homeless agent keeps back. */
	private static int spareWood(AgentMind mind, boolean hasHome) {
		int wood = 0;
		for (Possession p : mind.possessions()) {
			if (p.itemId().endsWith("_planks")) {
				wood += p.quantity();
			} else if (ItemKinds.isLog(p.itemId())) {
				wood += 4 * p.quantity();
			}
		}
		return wood - (hasHome ? 0 : HOMELESS_WOOD_RESERVE);
	}

	private static boolean ensureSticks(AgentMind mind, int needed, boolean hasHome, long tick) {
		while (mind.countOf(STICK) < needed) {
			if (spareWood(mind, hasHome) < 2 || !takePlanks(mind, 2, tick)) {
				return false;
			}
			mind.receiveItem(tick, STICK, 4);
		}
		return true;
	}

	/** Takes planks, splitting logs into four when there aren't enough. */
	private static boolean takePlanks(AgentMind mind, int needed, long tick) {
		int guard = 0;
		while (guard++ < 16) {
			for (Possession p : mind.possessions()) {
				if (p.itemId().endsWith("_planks") && p.quantity() >= needed) {
					return mind.takeItem(p.itemId(), needed);
				}
			}
			Optional<String> log = mind.possessions().stream().map(Possession::itemId)
					.filter(ItemKinds::isLog).findFirst();
			if (log.isEmpty()) {
				return false;
			}
			Optional<String> planks = ItemKinds.planksFor(log.get());
			if (planks.isEmpty() || !mind.takeItem(log.get(), 1)) {
				return false;
			}
			mind.receiveItem(tick, planks.get(), 4);
		}
		return false;
	}

	/** "made a door", "made some torches". */
	private static void note(AgentMind mind, EventLog log, long tick, String what) {
		String made = what.startsWith("some ") ? what : "a " + what;
		mind.perceive(tick, "I made " + made + ".", 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " made " + made + ".", List.of());
	}
}
