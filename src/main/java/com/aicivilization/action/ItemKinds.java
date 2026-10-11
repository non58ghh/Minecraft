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

	/** Stone fit to build with: what digging stone gives, and what it's cut or fired into. */
	static final java.util.Set<String> STONE_MATERIALS = java.util.Set.of(
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:mossy_cobblestone", "minecraft:stone",
			"minecraft:smooth_stone", "minecraft:stone_bricks", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
			"minecraft:tuff", "minecraft:deepslate", "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:bricks",
			"minecraft:mud_bricks", "minecraft:deepslate_bricks", "minecraft:polished_andesite", "minecraft:polished_diorite",
			"minecraft:polished_granite", "minecraft:blackstone");

	/**
	 * Blocks agents will build with: logs, planks and stone, and any other
	 * solid, whole block they carry (dirt, glass, wool, terracotta...). Not
	 * blocks that fall (sand, gravel), not ores or blocks of precious metal
	 * and gems, not food, and not things that do something (chests,
	 * furnaces, redstone parts, TNT): those are worth more than a wall.
	 */
	public static boolean isBuildingMaterial(String itemId) {
		if (isStoneMaterial(itemId)) {
			return true;
		}
		Optional<Item> item = item(itemId);
		if (item.isEmpty()) {
			return false;
		}
		ItemStack stack = item.get().getDefaultInstance();
		if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) {
			return true;
		}
		if (!(item.get() instanceof BlockItem blockItem) || NOT_FOR_WALLS.contains(itemId) || itemId.endsWith("_ore")) {
			return false;
		}
		net.minecraft.world.level.block.Block block = blockItem.getBlock();
		return !(block instanceof net.minecraft.world.level.block.EntityBlock)
				&& !(block instanceof net.minecraft.world.level.block.FallingBlock)
				&& block.defaultBlockState().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
						net.minecraft.core.BlockPos.ZERO);
	}

	/** Whole blocks worth more than a wall, or that don't stay put. */
	static final java.util.Set<String> NOT_FOR_WALLS = java.util.Set.of(
			"minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block", "minecraft:emerald_block",
			"minecraft:lapis_block", "minecraft:redstone_block", "minecraft:coal_block", "minecraft:copper_block",
			"minecraft:netherite_block", "minecraft:raw_iron_block", "minecraft:raw_gold_block", "minecraft:raw_copper_block",
			"minecraft:amethyst_block", "minecraft:crafting_table", "minecraft:tnt", "minecraft:note_block",
			"minecraft:target", "minecraft:redstone_lamp", "minecraft:observer", "minecraft:piston", "minecraft:sticky_piston",
			"minecraft:melon", "minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:jack_o_lantern",
			"minecraft:hay_block", "minecraft:dried_kelp_block", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
			"minecraft:bookshelf", "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:sponge", "minecraft:wet_sponge",
			"minecraft:slime_block", "minecraft:honey_block", "minecraft:honeycomb_block", "minecraft:bedrock",
			"minecraft:spawner", "minecraft:infested_stone", "minecraft:magma_block", "minecraft:glowstone",
			"minecraft:sea_lantern", "minecraft:shroomlight", "minecraft:beacon");

	/** Stone and brick it can build with. */
	public static boolean isStoneMaterial(String itemId) {
		return STONE_MATERIALS.contains(itemId);
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
