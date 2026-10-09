package com.aicivilization.world;

import com.aicivilization.mind.RecipeBook;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every crafting and smelting recipe in the game, as plain item ids, read
 * from the server's own recipe data at start and on {@code /reload}.
 *
 * <p>This is the game's answer key, so it stays outside the mind: it is used
 * to check that a goal names a real item, to confirm what a craft or a
 * smelt actually produced, and to give new agents what they know from the
 * start. Agents never plan from it; they plan from their own
 * {@link RecipeBook}.
 */
public final class RecipeCatalog {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");

	/** A recipe as the game has it: like a book entry, without anyone having learned it. */
	public record Entry(String result, int count, List<RecipeBook.Ingredient> ingredients, RecipeBook.Station station) {
		public RecipeBook.Recipe learnedAs(RecipeBook.Learned learned) {
			return new RecipeBook.Recipe(result, count, ingredients, station, learned);
		}
	}

	private static volatile RecipeCatalog current = new RecipeCatalog(Map.of(), Map.of());

	private final Map<String, List<Entry>> byResult;
	/** Smelting by input item: what a furnace turns it into. */
	private final Map<String, Entry> smeltingByInput;

	private RecipeCatalog(Map<String, List<Entry>> byResult, Map<String, Entry> smeltingByInput) {
		this.byResult = byResult;
		this.smeltingByInput = smeltingByInput;
	}

	public static RecipeCatalog get() {
		return current;
	}

	public static void rebuild(MinecraftServer server) {
		Map<String, List<Entry>> byResult = new HashMap<>();
		Map<String, Entry> smelting = new HashMap<>();
		int crafting = 0;
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			try {
				if (holder.value() instanceof CraftingRecipe recipe && !recipe.isSpecial()
						&& (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
					ItemStack result = recipe.assemble(CraftingInput.EMPTY);
					if (result.isEmpty()) {
						continue;
					}
					List<Ingredient> slots = recipe.placementInfo().ingredients();
					boolean table = recipe instanceof ShapedRecipe shaped
							? shaped.getWidth() > 2 || shaped.getHeight() > 2
							: slots.size() > 4;
					add(byResult, new Entry(id(result.getItem()), result.getCount(), group(slots),
							table ? RecipeBook.Station.TABLE : RecipeBook.Station.NONE));
					crafting++;
				} else if (holder.value() instanceof AbstractCookingRecipe cooking
						&& cooking.getType() == RecipeType.SMELTING) {
					ItemStack result = cooking.assemble(new SingleRecipeInput(ItemStack.EMPTY));
					if (result.isEmpty()) {
						continue;
					}
					Entry entry = new Entry(id(result.getItem()), result.getCount(), group(List.of(cooking.input())),
							RecipeBook.Station.FURNACE);
					add(byResult, entry);
					entry.ingredients().get(0).options().forEach(input -> smelting.putIfAbsent(input, entry));
				}
			} catch (RuntimeException e) {
				// One odd recipe (a datapack's, say) shouldn't cost the rest.
				LOGGER.debug("Skipped recipe {}", holder.id(), e);
			}
		}
		byResult.replaceAll((k, v) -> List.copyOf(v));
		current = new RecipeCatalog(Map.copyOf(byResult), Map.copyOf(smelting));
		LOGGER.info("Recipe catalogue: {} crafting recipes, {} smeltable items, {} results.", crafting, smelting.size(),
				byResult.size());
	}

	/** Whether {@code itemId} names a real item. */
	public static boolean isItem(String itemId) {
		Identifier key = Identifier.tryParse(itemId);
		return key != null && BuiltInRegistries.ITEM.containsKey(key);
	}

	public List<Entry> recipesFor(String itemId) {
		return byResult.getOrDefault(itemId, List.of());
	}

	/**
	 * The plainest way to make {@code itemId}: the fewest inputs, and of
	 * those the one that takes the widest choice of materials (sticks from
	 * any planks, not from bamboo).
	 */
	public Optional<Entry> plainestRecipeFor(String itemId) {
		return recipesFor(itemId).stream()
				.min(Comparator.comparingInt((Entry e) -> e.ingredients().stream().mapToInt(RecipeBook.Ingredient::count).sum())
						.thenComparing(e -> -e.ingredients().stream().mapToInt(i -> i.options().size()).sum()));
	}

	public Optional<Entry> smeltingOf(String inputItemId) {
		return Optional.ofNullable(smeltingByInput.get(inputItemId));
	}

	public int size() {
		return byResult.size();
	}

	private static void add(Map<String, List<Entry>> byResult, Entry entry) {
		byResult.computeIfAbsent(entry.result(), k -> new ArrayList<>()).add(entry);
	}

	/** Identical slots become one ingredient with a count: a stick is two of any planks. */
	private static List<RecipeBook.Ingredient> group(List<Ingredient> slots) {
		Map<List<String>, Integer> counts = new LinkedHashMap<>();
		for (Ingredient slot : slots) {
			List<String> options = slot.items().map(holder -> id(holder.value())).sorted().toList();
			if (!options.isEmpty()) {
				counts.merge(options, 1, Integer::sum);
			}
		}
		List<RecipeBook.Ingredient> out = new ArrayList<>();
		counts.forEach((options, count) -> out.add(new RecipeBook.Ingredient(options, count)));
		return Collections.unmodifiableList(out);
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
