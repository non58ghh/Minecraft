package com.aicivilization.action;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What an item id means in Minecraft terms. Minds only hold item ids as
 * plain strings; the embodiment layer uses this to decide what is food and
 * what can be built with.
 */
public final class ItemKinds {

	private ItemKinds() {
	}

	public static String idOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static Optional<Item> item(String itemId) {
		Identifier id = Identifier.tryParse(itemId);
		if (id == null) {
			return Optional.empty();
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return item == null ? Optional.empty() : Optional.of(item);
	}

	/** Hunger points the item restores when eaten, or 0 if it isn't food. */
	public static int nutrition(String itemId) {
		return item(itemId)
				.map(i -> i.getDefaultInstance().get(DataComponents.FOOD))
				.map(FoodProperties::nutrition)
				.orElse(0);
	}

	/** Logs and planks: blocks agents will build with. */
	public static boolean isBuildingMaterial(String itemId) {
		return item(itemId)
				.map(i -> {
					ItemStack stack = i.getDefaultInstance();
					return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS);
				})
				.orElse(false);
	}

	public static boolean isLog(String itemId) {
		return item(itemId).map(i -> i.getDefaultInstance().is(ItemTags.LOGS)).orElse(false);
	}

	/**
	 * The planks a log splits into ({@code minecraft:stripped_oak_log} and
	 * {@code minecraft:oak_wood} both give {@code minecraft:oak_planks}), if
	 * there is such an item.
	 */
	public static Optional<String> planksFor(String logId) {
		String id = logId.replace("stripped_", "");
		for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {
			if (id.endsWith(suffix)) {
				String planks = id.substring(0, id.length() - suffix.length()) + "_planks";
				return item(planks).filter(i -> i.getDefaultInstance().is(ItemTags.PLANKS)).map(i -> planks);
			}
		}
		return Optional.empty();
	}

	public static Optional<BlockState> blockFor(String itemId) {
		return item(itemId)
				.filter(i -> i instanceof BlockItem)
				.map(i -> ((BlockItem) i).getBlock().defaultBlockState());
	}

	/** {@code minecraft:oak_log} becomes {@code oak log}. */
	public static String displayName(String itemId) {
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		return path.replace('_', ' ');
	}
}
