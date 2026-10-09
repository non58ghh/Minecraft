package com.aicivilization.world;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.RecipeBook;
import java.util.List;

/**
 * What an agent knows how to make before it has made anything here: the
 * everyday things every agent already does (planks, sticks, a table, wooden
 * and stone tools, bread, a furnace, cooked food), the shape of a tool of
 * any material, and where wood, stone, coal and iron come from. Turning raw
 * iron into ingots is known only to the more curious; the rest have to find
 * it out.
 */
public final class StartingKnowledge {

	private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
			"mangrove", "cherry", "pale_oak");
	private static final List<String> TOOLS = List.of("pickaxe", "axe", "shovel", "sword", "hoe");
	private static final List<String> TOOL_MATERIALS = List.of("wooden", "stone", "iron");
	private static final List<String> EVERYDAY = List.of("stick", "crafting_table", "furnace", "bread", "torch");
	private static final List<String> RAW_FOODS = List.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cod",
			"salmon", "potato");
	/** Curious enough to have picked up smelting iron before coming here. */
	private static final double KNOWS_IRON_CURIOSITY = 0.65;

	private StartingKnowledge() {
	}

	/** Fills an empty book. Does nothing if the catalogue isn't ready or the agent already knows things. */
	public static void seedIfEmpty(AgentMind mind, RecipeCatalog catalog) {
		RecipeBook book = mind.recipeBook();
		if (catalog.size() == 0 || !book.recipes().isEmpty()) {
			return;
		}
		RecipeBook.Learned start = RecipeBook.Learned.atStart();
		for (String wood : WOODS) {
			learn(book, catalog, wood + "_planks", start);
			book.learn(new RecipeBook.Source(mc(wood + "_log"), mc(wood + "_log"), "", start));
		}
		EVERYDAY.forEach(item -> learn(book, catalog, item, start));
		for (String material : TOOL_MATERIALS) {
			for (String tool : TOOLS) {
				learn(book, catalog, material + "_" + tool, start);
			}
		}
		for (String food : RAW_FOODS) {
			catalog.smeltingOf(mc(food)).ifPresent(e -> book.learn(e.learnedAs(start)));
		}
		if (mind.personality().curiosity() >= KNOWS_IRON_CURIOSITY) {
			catalog.smeltingOf(mc("raw_iron")).ifPresent(e -> book.learn(e.learnedAs(start)));
		}
		book.learn(new RecipeBook.Source(mc("cobblestone"), mc("stone"), mc("wooden_pickaxe"), start));
		book.learn(new RecipeBook.Source(mc("coal"), mc("coal_ore"), mc("wooden_pickaxe"), start));
		book.learn(new RecipeBook.Source(mc("raw_iron"), mc("iron_ore"), mc("stone_pickaxe"), start));
		book.learn(new RecipeBook.Source(mc("wheat_seeds"), mc("short_grass"), "", start));
	}

	private static void learn(RecipeBook book, RecipeCatalog catalog, String item, RecipeBook.Learned start) {
		catalog.plainestRecipeFor(mc(item)).ifPresent(e -> book.learn(e.learnedAs(start)));
	}

	private static String mc(String path) {
		return "minecraft:" + path;
	}
}
