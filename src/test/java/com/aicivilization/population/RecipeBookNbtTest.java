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

	@Test
	void whatItLearnedOrHeardAboutSaplingsSurvivesSaveAndLoad() {
		AgentMind seer = new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0), new Personality(0.8, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		seer.recipeBook().learnPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("saw", "", null, 900));
		AgentMind loadedSeer = AgentMindNbt.read(AgentMindNbt.write(seer));
		assertEquals(true, loadedSeer.recipeBook().knowsPractice(RecipeBook.REPLANTING));
		assertEquals("saw", loadedSeer.recipeBook().practices().get(RecipeBook.REPLANTING).how());

		UUID teller = seer.identity().id();
		AgentMind listener = new AgentMind(new Identity(UUID.randomUUID(), "Bram", 0), new Personality(0.2, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		listener.recipeBook().hearPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("told", "Iris", teller, 950));
		AgentMind loadedListener = AgentMindNbt.read(AgentMindNbt.write(listener));
		assertEquals(false, loadedListener.recipeBook().knowsPractice(RecipeBook.REPLANTING));
		assertEquals(true, loadedListener.recipeBook().heardOfPractice(RecipeBook.REPLANTING));
		assertEquals(teller, loadedListener.recipeBook().heardPractices().get(RecipeBook.REPLANTING).fromId());
	}

	@Test
	void placesSurviveSaveAndLoad() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Linnea", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID owner = UUID.randomUUID();
		mind.places().note(com.aicivilization.mind.Places.Kind.FIELD, 80, 70, -95, 100, null, true);
		mind.places().note(com.aicivilization.mind.Places.Kind.HOME, -57, 63, 108, 200, owner, false);
		AgentMind loaded = AgentMindNbt.read(AgentMindNbt.write(mind));
		var field = loaded.places().of(com.aicivilization.mind.Places.Kind.FIELD, 300).get(0);
		assertEquals(80, field.x());
		assertEquals(-95, field.z());
		assertEquals(true, field.mine());
		assertEquals(owner, loaded.places().of(com.aicivilization.mind.Places.Kind.HOME, 300).get(0).about());
	}

	@Test
	void heardOfPlacesAndFailuresSurviveSaveAndLoad() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Linnea", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID teller = UUID.randomUUID();
		mind.hearOfPlace(500, com.aicivilization.mind.Places.Kind.WOODS, 40, 64, 40, 450, teller, "Iris",
				"Iris told me there are trees about 60 blocks east of where we talked.");
		mind.noteFailure(com.aicivilization.mind.Lessons.Failure.NO_TREES, 0, 0, 100, false);
		AgentMind loaded = AgentMindNbt.read(AgentMindNbt.write(mind));
		var woods = loaded.places().of(com.aicivilization.mind.Places.Kind.WOODS, 600).get(0);
		assertEquals(teller, woods.teller().id());
		assertEquals("Iris", woods.teller().name());
		assertEquals(450, woods.tick());
		assertEquals(mind.lessons().misses(), loaded.lessons().misses());
	}

	@Test
	void deathsKnownAndParentsSurviveSaveAndLoad() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 5), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID bram = UUID.randomUUID(), mother = UUID.randomUUID(), father = UUID.randomUUID();
		mind.setParents(java.util.List.of(mother, father));
		mind.learnOfDeath(10, bram, "Bram", "drowned", AgentMind.DeathNews.FOUND, null, null);
		AgentMind loaded = AgentMindNbt.read(AgentMindNbt.write(mind));
		assertEquals(java.util.List.of(mother, father), loaded.parents());
		assertEquals("drowned", loaded.knownDead().get(bram).how());
		assertEquals("Bram", loaded.knownDead().get(bram).name());
	}
}
