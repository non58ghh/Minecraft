package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * An enchanting table at home, used as a player would: lapis and experience
 * levels in, a random enchantment out, from the game's own enchanting rules,
 * stronger with bookshelves around the table. It enchants its sword first,
 * then its armour, then its tools. The enchantments are real: a sharp sword
 * hits harder, protection softens blows, unbreaking makes things last.
 */
public final class Enchanting {

	public static final String TABLE = "minecraft:enchanting_table";
	static final String LAPIS = "minecraft:lapis_lazuli";
	static final String UNBREAKING = "minecraft:unbreaking";
	/** The most bookshelves that count, as in vanilla. */
	static final int MAX_SHELVES = 15;

	private Enchanting() {
	}

	/**
	 * At home: sets up the enchanting table it carries, then, with the lapis
	 * and the levels for it, enchants the first thing it has that isn't yet.
	 * Returns whether it did anything.
	 */
	public static boolean tend(AgentEntity self, ServerLevel world, AgentMind mind, Home home, BlockPos origin, long tick,
			EventLog log) {
		Optional<BlockPos> table = Verbs.findNear(world, origin, Blocks.ENCHANTING_TABLE, 6);
		if (table.isEmpty()) {
			if (mind.countOf(TABLE) == 0) {
				return false;
			}
			Optional<BlockPos> spot = HomeChest.spotFor(world, home, origin);
			if (spot.isEmpty() || !mind.takeItem(TABLE, 1)) {
				return false;
			}
			world.setBlockAndUpdate(spot.get(), Blocks.ENCHANTING_TABLE.defaultBlockState());
			self.swing(InteractionHand.MAIN_HAND);
			mind.perceive(tick, "I set up an enchanting table at my home.", 0.5, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " set up an enchanting table at " + home.design().name() + ".", List.of());
			return true;
		}
		if (mind.level() < 1 || mind.countOf(LAPIS) < 1 || self.blockPosition().distSqr(table.get()) > 36) {
			return false;
		}
		String item = nextToEnchant(mind);
		if (item == null) {
			return false;
		}
		ItemStack stack = stackOf(item);
		if (stack.isEmpty() || !stack.isEnchantable()) {
			return false;
		}
		RandomSource random = self.getRandom();
		int shelves = bookshelves(world, table.get());
		// The three offers on the table; it takes the best it can pay for.
		for (int slot = 2; slot >= 0; slot--) {
			int cost = EnchantmentHelper.getEnchantmentCost(random, slot, shelves, stack);
			if (cost <= 0 || mind.level() < cost || mind.countOf(LAPIS) < slot + 1) {
				continue;
			}
			HolderLookup.RegistryLookup<Enchantment> registry = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			ItemStack done = EnchantmentHelper.enchantItem(random, stack.copy(), cost, world.registryAccess(),
					registry.get(EnchantmentTags.IN_ENCHANTING_TABLE));
			ItemEnchantments got = EnchantmentHelper.getEnchantmentsForCrafting(done);
			if (got.isEmpty() || !mind.spendLevels(slot + 1) || !mind.takeItem(LAPIS, slot + 1)) {
				return false;
			}
			List<String> spec = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (var e : got.entrySet()) {
				e.getKey().unwrapKey().ifPresent(k -> spec.add(k.identifier() + "=" + e.getIntValue()));
				names.add(Enchantment.getFullname(e.getKey(), e.getIntValue()).getString());
			}
			mind.enchant(item, String.join(",", spec));
			self.swing(InteractionHand.MAIN_HAND);
			String what = ItemKinds.displayName(item) + " (" + String.join(", ", names) + ")";
			mind.perceive(tick, "I enchanted my " + what + " at the enchanting table.", 0.6, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id()),
					mind.identity().name() + " enchanted their " + what + ".", List.of());
			Armour.dress(self, mind);
			return true;
		}
		return false;
	}

	/** The first of its sword, armour (body first) and tools that has no enchantment yet. */
	static String nextToEnchant(AgentMind mind) {
		List<String> order = new ArrayList<>();
		Crafting.best(mind, Crafting.Tool.SWORD).ifPresent(order::add);
		for (String suffix : List.of("_chestplate", "_helmet", "_leggings", "_boots")) {
			String piece = Armour.best(mind, suffix);
			if (piece != null) {
				order.add(piece);
			}
		}
		Crafting.best(mind, Crafting.Tool.PICKAXE).ifPresent(order::add);
		Crafting.best(mind, Crafting.Tool.AXE).ifPresent(order::add);
		for (String item : order) {
			if (mind.enchantmentsOn(item).isEmpty()) {
				return item;
			}
		}
		return null;
	}

	/** Bookshelves in the ring two blocks out from the table, at its level and the one above, as in vanilla. */
	static int bookshelves(ServerLevel world, BlockPos table) {
		int n = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
					continue;
				}
				for (int dy = 0; dy <= 1; dy++) {
					if (world.getBlockState(table.offset(dx, dy, dz)).is(Blocks.BOOKSHELF)) {
						n++;
					}
				}
			}
		}
		return Math.min(MAX_SHELVES, n);
	}

	/** The level of this enchantment ("minecraft:unbreaking") in a spec like "minecraft:sharpness=2,minecraft:unbreaking=1". */
	static int levelIn(String spec, String enchantment) {
		for (String part : spec.split(",")) {
			int eq = part.indexOf('=');
			if (eq > 0 && part.substring(0, eq).equals(enchantment)) {
				try {
					return Integer.parseInt(part.substring(eq + 1));
				} catch (NumberFormatException e) {
					return 0;
				}
			}
		}
		return 0;
	}

	/** Whether unbreaking spares this item a use, as vanilla's does: with level L, L in L+1 uses cost nothing. */
	public static boolean spared(AgentMind mind, String itemId, RandomSource random) {
		int level = levelIn(mind.enchantmentsOn(itemId), UNBREAKING);
		return level > 0 && random.nextInt(level + 1) > 0;
	}

	/** The item as it would be held or worn: with its enchantments on, if it has any. */
	public static ItemStack stackFor(Level level, AgentMind mind, String itemId) {
		ItemStack stack = stackOf(itemId);
		String spec = mind.enchantmentsOn(itemId);
		if (stack.isEmpty() || spec.isEmpty()) {
			return stack;
		}
		HolderLookup.RegistryLookup<Enchantment> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		EnchantmentHelper.updateEnchantments(stack, m -> {
			for (String part : spec.split(",")) {
				int eq = part.indexOf('=');
				Identifier key = eq > 0 ? Identifier.tryParse(part.substring(0, eq)) : null;
				if (key == null) {
					continue;
				}
				Optional<? extends Holder<Enchantment>> holder = registry.get(ResourceKey.create(Registries.ENCHANTMENT, key));
				holder.ifPresent(h -> m.set(h, levelIn(spec, key.toString())));
			}
		});
		return stack;
	}

	private static ItemStack stackOf(String itemId) {
		Identifier key = Identifier.tryParse(itemId);
		return key == null ? ItemStack.EMPTY
				: BuiltInRegistries.ITEM.getOptional(key).map(i -> i.getDefaultInstance()).orElse(ItemStack.EMPTY);
	}
}
