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
	/** Uses before a tool breaks, as in vanilla. */
	private static final int WOODEN_USES = 59;
	private static final int STONE_USES = 131;
	/** Planks kept back for a first home, so tools don't eat the walls. */
	private static final int HOMELESS_WOOD_RESERVE = 24;

	private Crafting() {
	}

	/** The best tool of this kind the agent carries, if any: stone before wood. */
	public static Optional<String> best(AgentMind mind, Tool tool) {
		if (mind.countOf(STONE + tool.suffix) > 0) {
			return Optional.of(STONE + tool.suffix);
		}
		if (mind.countOf(WOODEN + tool.suffix) > 0) {
			return Optional.of(WOODEN + tool.suffix);
		}
		return Optional.empty();
	}

	public static boolean isStone(String toolId) {
		return toolId.startsWith(STONE);
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

	/**
	 * Holds the best tool of this kind in hand (or nothing) so it counts in a
	 * fight and others can see what the agent is doing.
	 */
	public static void hold(AgentEntity self, AgentMind mind, Tool tool) {
		Optional<String> best = tool == null ? Optional.empty() : best(mind, tool);
		ItemStack stack = best.flatMap(id -> BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(id)))
				.map(item -> item.getDefaultInstance())
				.orElse(ItemStack.EMPTY);
		if (!ItemStack.isSameItem(stack, self.getMainHandItem())) {
			self.setItemInHand(InteractionHand.MAIN_HAND, stack);
		}
	}

	/** One use of a tool: it may break, like a vanilla tool running out of durability. */
	public static void wear(AgentEntity self, AgentMind mind, String toolId, long tick, EventLog log) {
		RandomSource random = self.getRandom();
		int uses = isStone(toolId) ? STONE_USES : WOODEN_USES;
		if (random.nextInt(uses) == 0 && mind.takeItem(toolId, 1)) {
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

	private static void note(AgentMind mind, EventLog log, long tick, String what) {
		mind.perceive(tick, "I made a " + what + ".", 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " made a " + what + ".", List.of());
	}
}
