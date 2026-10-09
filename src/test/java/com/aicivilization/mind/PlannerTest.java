package com.aicivilization.mind;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

	private static final RecipeBook.Learned START = RecipeBook.Learned.atStart();

	private static RecipeBook.Ingredient ing(int count, String... options) {
		return new RecipeBook.Ingredient(List.of(options), count);
	}

	private static void recipe(RecipeBook book, String result, int count, RecipeBook.Station station,
			RecipeBook.Ingredient... ingredients) {
		book.learn(new RecipeBook.Recipe(result, count, List.of(ingredients), station, START));
	}

	/** Roughly what StartingKnowledge gives a curious agent. */
	private static RecipeBook everydayBook(boolean knowsIron) {
		RecipeBook book = new RecipeBook();
		recipe(book, "minecraft:oak_planks", 4, RecipeBook.Station.NONE, ing(1, "minecraft:oak_log"));
		recipe(book, "minecraft:birch_planks", 4, RecipeBook.Station.NONE, ing(1, "minecraft:birch_log"));
		recipe(book, "minecraft:stick", 4, RecipeBook.Station.NONE,
				ing(2, "minecraft:birch_planks", "minecraft:oak_planks"));
		recipe(book, "minecraft:crafting_table", 1, RecipeBook.Station.NONE,
				ing(4, "minecraft:birch_planks", "minecraft:oak_planks"));
		recipe(book, "minecraft:wooden_pickaxe", 1, RecipeBook.Station.TABLE,
				ing(3, "minecraft:birch_planks", "minecraft:oak_planks"), ing(2, "minecraft:stick"));
		recipe(book, "minecraft:stone_pickaxe", 1, RecipeBook.Station.TABLE,
				ing(3, "minecraft:cobblestone"), ing(2, "minecraft:stick"));
		recipe(book, "minecraft:furnace", 1, RecipeBook.Station.TABLE, ing(8, "minecraft:cobblestone"));
		recipe(book, "minecraft:iron_pickaxe", 1, RecipeBook.Station.TABLE,
				ing(3, "minecraft:iron_ingot"), ing(2, "minecraft:stick"));
		if (knowsIron) {
			recipe(book, "minecraft:iron_ingot", 1, RecipeBook.Station.FURNACE, ing(1, "minecraft:raw_iron"));
		}
		book.learn(new RecipeBook.Source("minecraft:oak_log", "minecraft:oak_log", "", START));
		book.learn(new RecipeBook.Source("minecraft:birch_log", "minecraft:birch_log", "", START));
		book.learn(new RecipeBook.Source("minecraft:cobblestone", "minecraft:stone", "minecraft:wooden_pickaxe", START));
		book.learn(new RecipeBook.Source("minecraft:coal", "minecraft:coal_ore", "minecraft:wooden_pickaxe", START));
		book.learn(new RecipeBook.Source("minecraft:raw_iron", "minecraft:iron_ore", "minecraft:stone_pickaxe", START));
		return book;
	}

	private static int indexOf(List<Planner.Step> steps, Planner.StepKind kind, String item) {
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i).kind() == kind && steps.get(i).item().equals(item)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void anIronPickaxeFromNothing() {
		Planner.Result plan = Planner.plan(everydayBook(true), Map.of(), "minecraft:iron_pickaxe", 1);
		assertTrue(plan.ok(), "gap: " + plan.gap());
		List<Planner.Step> steps = plan.steps();
		int stonePick = indexOf(steps, Planner.StepKind.CRAFT, "minecraft:stone_pickaxe");
		int iron = indexOf(steps, Planner.StepKind.GATHER, "minecraft:raw_iron");
		int smelt = indexOf(steps, Planner.StepKind.SMELT, "minecraft:iron_ingot");
		assertTrue(stonePick >= 0 && iron > stonePick && smelt > iron, steps.toString());
		assertEquals(Planner.StepKind.CRAFT, steps.get(steps.size() - 1).kind());
		assertEquals("minecraft:iron_pickaxe", steps.get(steps.size() - 1).item());
		assertEquals(3, steps.get(iron).count());
		assertEquals("minecraft:stone_pickaxe", steps.get(iron).tool());
		// Tools and stations are made once and kept, not remade for every use.
		assertEquals(1, steps.stream().filter(s -> s.item().equals("minecraft:wooden_pickaxe")).count());
		assertEquals(1, steps.stream().filter(s -> s.item().equals("minecraft:crafting_table")).count());
		assertEquals(1, steps.stream().filter(s -> s.item().equals("minecraft:furnace")).count());
		assertEquals(1, steps.get(smelt).inputs().get("minecraft:coal"));
	}

	@Test
	void notKnowingHowToSmeltIronIsAGap() {
		Planner.Result plan = Planner.plan(everydayBook(false), Map.of(), "minecraft:iron_pickaxe", 1);
		assertFalse(plan.ok());
		assertEquals("minecraft:iron_ingot", plan.gap().orElseThrow());
	}

	@Test
	void whatIsCarriedIsUsedFirst() {
		Planner.Result plan = Planner.plan(everydayBook(true), Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2,
				"minecraft:crafting_table", 1), "minecraft:iron_pickaxe", 1);
		assertTrue(plan.ok());
		assertEquals(1, plan.steps().size());
		assertEquals("minecraft:iron_pickaxe", plan.steps().get(0).item());
	}

	@Test
	void planksComeFromTheLogsAlreadyCarried() {
		Planner.Result plan = Planner.plan(everydayBook(true), Map.of("minecraft:birch_log", 2), "minecraft:stick", 4);
		assertTrue(plan.ok());
		assertEquals("minecraft:birch_planks", plan.steps().get(0).item());
		assertEquals(-1, indexOf(plan.steps(), Planner.StepKind.GATHER, "minecraft:oak_log"));
	}

	@Test
	void recipesThatGoRoundInCirclesEndInAGap() {
		RecipeBook book = new RecipeBook();
		recipe(book, "minecraft:iron_block", 1, RecipeBook.Station.TABLE, ing(9, "minecraft:iron_ingot"));
		recipe(book, "minecraft:iron_ingot", 9, RecipeBook.Station.NONE, ing(1, "minecraft:iron_block"));
		Planner.Result plan = Planner.plan(book, Map.of(), "minecraft:iron_ingot", 1);
		assertFalse(plan.ok());
	}
}
