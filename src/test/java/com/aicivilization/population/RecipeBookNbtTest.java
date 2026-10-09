package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.RecipeBook;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeBookNbtTest {

	@Test
	void aRecipeBookSurvivesSaveAndLoad() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID teller = UUID.randomUUID();
		mind.recipeBook().learn(new RecipeBook.Recipe("minecraft:stick", 4,
				List.of(new RecipeBook.Ingredient(List.of("minecraft:oak_planks", "minecraft:birch_planks"), 2)),
				RecipeBook.Station.NONE, RecipeBook.Learned.atStart()));
		mind.recipeBook().hear(new RecipeBook.Recipe("minecraft:iron_ingot", 1,
				List.of(new RecipeBook.Ingredient(List.of("minecraft:raw_iron"), 1)), RecipeBook.Station.FURNACE,
				new RecipeBook.Learned("told", "Iris", teller, 42)));
		mind.recipeBook().learn(new RecipeBook.Source("minecraft:coal", "minecraft:coal_ore", "minecraft:wooden_pickaxe",
				RecipeBook.Learned.byDoing(7)));

		mind.addGoal(5, "make an iron pickaxe", 0.7, com.aicivilization.mind.IntentType.GATHER_MATERIALS,
				"minecraft:iron_pickaxe", 1);

		AgentMind loaded = AgentMindNbt.read(AgentMindNbt.write(mind));

		com.aicivilization.mind.Goal goal = loaded.goals().get(0);
		assertEquals("minecraft:iron_pickaxe", goal.targetItem());
		assertEquals(1, goal.targetCount());

		RecipeBook book = loaded.recipeBook();
		RecipeBook.Recipe stick = book.recipeFor("minecraft:stick").orElseThrow();
		assertEquals(4, stick.count());
		assertEquals(List.of("minecraft:oak_planks", "minecraft:birch_planks"), stick.ingredients().get(0).options());
		assertEquals(2, stick.ingredients().get(0).count());
		RecipeBook.Recipe hint = book.hints().iterator().next();
		assertEquals(RecipeBook.Station.FURNACE, hint.station());
		assertEquals(teller, hint.learned().fromId());
		assertEquals("made", book.sourceOf("minecraft:coal").orElseThrow().learned().how());
	}
}
