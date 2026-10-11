package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Possession;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Armour it carries, it wears: the best piece it has for each of head, body,
 * legs and feet, which really takes the edge off a blow. Each blow it takes
 * may wear a piece out, as armour runs out of durability in vanilla; the
 * better the material, the longer it lasts.
 */
public final class Armour {

	/** Each slot and the item name ending that goes in it. */
	static final Map<EquipmentSlot, String> SLOTS = Map.of(
			EquipmentSlot.HEAD, "_helmet", EquipmentSlot.CHEST, "_chestplate",
			EquipmentSlot.LEGS, "_leggings", EquipmentSlot.FEET, "_boots");
	/** Materials, worst to best. */
	static final List<String> MATERIALS = List.of("leather", "golden", "copper", "chainmail", "iron", "diamond", "netherite");
	/** Blows a piece of each material takes, on average, before it wears out. */
	private static final int[] BLOWS = {20, 25, 30, 40, 60, 120, 150};

	private Armour() {
	}

	/** Whether this item is a piece of armour. */
	public static boolean isArmour(String itemId) {
		return rank(itemId) >= 0;
	}

	/** Its material's place among {@link #MATERIALS}, or -1 if it isn't armour. */
	static int rank(String itemId) {
		String name = itemId.replaceFirst("^[^:]*:", "");
		for (String suffix : SLOTS.values()) {
			if (name.endsWith(suffix)) {
				return MATERIALS.indexOf(name.substring(0, name.length() - suffix.length()));
			}
		}
		return -1;
	}

	/** The best piece it carries for this slot, or null. */
	static String best(AgentMind mind, String suffix) {
		String best = null;
		int bestRank = -1;
		for (Possession p : mind.possessions()) {
			int r = p.itemId().endsWith(suffix) ? rank(p.itemId()) : -1;
			if (r > bestRank && p.quantity() > 0) {
				best = p.itemId();
				bestRank = r;
			}
		}
		return best;
	}

	/** Puts on the best of what it carries, and takes off what it no longer has. */
	public static void dress(AgentEntity self, AgentMind mind) {
		for (Map.Entry<EquipmentSlot, String> slot : SLOTS.entrySet()) {
			String id = best(mind, slot.getValue());
			ItemStack stack = id == null ? ItemStack.EMPTY : Enchanting.stackFor(self.level(), mind, id);
			if (!ItemStack.isSameItemSameComponents(stack, self.getItemBySlot(slot.getKey()))) {
				self.setItemSlot(slot.getKey(), stack);
				// What it wears is in its pack too (left with its things when it dies): nothing drops from the body.
				self.setDropChance(slot.getKey(), 0.0f);
			}
		}
	}

	/** It took a blow: each piece worn may wear out. */
	public static void blow(AgentEntity self, AgentMind mind, long tick, EventLog log) {
		for (Map.Entry<EquipmentSlot, String> slot : SLOTS.entrySet()) {
			String id = best(mind, slot.getValue());
			if (id == null || self.getRandom().nextInt(BLOWS[rank(id)]) != 0 || Enchanting.spared(mind, id, self.getRandom())
					|| !mind.takeItem(id, 1)) {
				continue;
			}
			String name = ItemKinds.displayName(id);
			mind.perceive(tick, "My " + name + " wore through.", 0.3, Set.of());
			log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
					mind.identity().name() + "'s " + name + " wore through.", List.of());
		}
		dress(self, mind);
	}
}
