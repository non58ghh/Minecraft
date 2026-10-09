package com.aicivilization.mind;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBookTest {

	private static RecipeBook.Recipe furnace(RecipeBook.Learned learned) {
		return new RecipeBook.Recipe("minecraft:furnace", 1,
				List.of(new RecipeBook.Ingredient(List.of("minecraft:cobblestone"), 8)), RecipeBook.Station.TABLE, learned);
	}

	@Test
	void aHintIsNotKnowledge() {
		RecipeBook book = new RecipeBook();
		UUID teller = UUID.randomUUID();
		assertTrue(book.hear(furnace(new RecipeBook.Learned("told", "Iris", teller, 10))));
		assertFalse(book.knows("minecraft:furnace"));
		assertTrue(book.recipeFor("minecraft:furnace").isEmpty());
		assertEquals(1, book.hints().size());
	}

	@Test
	void makingSomethingHeardOfKeepsWhoToldIt() {
		RecipeBook book = new RecipeBook();
		UUID teller = UUID.randomUUID();
		book.hear(furnace(new RecipeBook.Learned("told", "Iris", teller, 10)));
		assertTrue(book.learn(furnace(RecipeBook.Learned.byDoing(50))));
		RecipeBook.Learned learned = book.recipeFor("minecraft:furnace").orElseThrow().learned();
		assertEquals("made", learned.how());
		assertEquals("Iris", learned.fromName());
		assertEquals(teller, learned.fromId());
		assertTrue(book.hints().isEmpty());
	}

	@Test
	void learningTwiceKeepsTheFirst() {
		RecipeBook book = new RecipeBook();
		assertTrue(book.learn(furnace(RecipeBook.Learned.atStart())));
		assertFalse(book.learn(furnace(RecipeBook.Learned.byDoing(99))));
		assertEquals("start", book.recipeFor("minecraft:furnace").orElseThrow().learned().how());
		assertFalse(book.hear(furnace(new RecipeBook.Learned("told", "Iris", null, 5))));
	}

	@Test
	void sourcesSayWhatToolTheyNeed() {
		RecipeBook book = new RecipeBook();
		book.learn(new RecipeBook.Source("minecraft:raw_iron", "minecraft:iron_ore", "minecraft:stone_pickaxe",
				RecipeBook.Learned.atStart()));
		assertEquals("minecraft:stone_pickaxe", book.sourceOf("minecraft:raw_iron").orElseThrow().tool());
		assertTrue(book.knows("minecraft:raw_iron"));
	}
}
