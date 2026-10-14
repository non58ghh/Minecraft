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

	/**
	 * The Phase 2 gate: three agents end up holding a recipe, and the chain of
	 * who-taught-whom, walked through each book's own {@code Learned.fromId},
	 * leads back to a single discoverer who made it from nothing taught.
	 */
	@Test
	void recipeChainTracesBackToOneDiscoverer() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();

		RecipeBook bookA = new RecipeBook();
		assertTrue(bookA.learn(furnace(RecipeBook.Learned.byDoing(10))));

		RecipeBook bookB = new RecipeBook();
		assertTrue(bookB.hear(furnace(new RecipeBook.Learned("told", "A", a, 20))));
		assertTrue(bookB.learn(furnace(RecipeBook.Learned.byDoing(25))));

		RecipeBook bookC = new RecipeBook();
		assertTrue(bookC.hear(furnace(new RecipeBook.Learned("told", "B", b, 30))));
		assertTrue(bookC.learn(furnace(RecipeBook.Learned.byDoing(35))));

		RecipeBook.Learned cLearned = bookC.recipeFor("minecraft:furnace").orElseThrow().learned();
		assertEquals(b, cLearned.fromId());
		RecipeBook.Learned bLearned = bookB.recipeFor("minecraft:furnace").orElseThrow().learned();
		assertEquals(a, bLearned.fromId());
		RecipeBook.Learned aLearned = bookA.recipeFor("minecraft:furnace").orElseThrow().learned();
		assertEquals("made", aLearned.how());
		assertEquals(null, aLearned.fromId());
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
