package com.aicivilization.world;

import com.aicivilization.mind.RecipeBook;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryTest {

	private static RecipeBook.Ingredient of(int n, String... options) {
		return new RecipeBook.Ingredient(List.of(options), n);
	}

	private static final RecipeCatalog CATALOG = RecipeCatalog.of(List.of(
			new RecipeCatalog.Entry("minecraft:iron_chestplate", 1, List.of(of(8, "minecraft:iron_ingot")), RecipeBook.Station.TABLE),
			new RecipeCatalog.Entry("minecraft:enchanting_table", 1, List.of(of(1, "minecraft:book"), of(2, "minecraft:diamond"),
					of(4, "minecraft:obsidian")), RecipeBook.Station.TABLE)));

	@Test
	void itWorksThingsOutOnlyFromWhatItKnowsOrHas() {
		RecipeBook book = new RecipeBook();
		assertTrue(Discovery.workable(book, Set.of(), "minecraft:iron_chestplate", CATALOG).isEmpty(),
				"no idea where iron comes from: nothing to work from");
		assertEquals("minecraft:iron_chestplate",
				Discovery.workable(book, Set.of("minecraft:iron_ingot"), "minecraft:iron_chestplate", CATALOG).get().result(),
				"iron in hand is enough to work out armour");
		book.learn(new RecipeBook.Source("minecraft:diamond", "minecraft:diamond_ore", "minecraft:iron_pickaxe",
				RecipeBook.Learned.atStart()));
		assertTrue(Discovery.workable(book, Set.of("minecraft:book"), "minecraft:enchanting_table", CATALOG).isEmpty(),
				"obsidian unknown");
		assertTrue(Discovery.workable(book, Set.of("minecraft:book", "minecraft:obsidian"), "minecraft:enchanting_table", CATALOG)
				.isPresent());
	}

	@Test
	void curiousMindsWorkThingsOutSooner() {
		assertTrue(Discovery.chance(0.9) > Discovery.chance(0.1));
	}
}
