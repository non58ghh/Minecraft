package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nobody starts out knowing saplings grow; hearing of it isn't knowing it. */
class PracticeTest {

	@Test
	void nobodyStartsOutKnowingAboutSaplings() {
		RecipeBook book = new RecipeBook();
		assertFalse(book.knowsPractice(RecipeBook.REPLANTING));
		assertFalse(book.heardOfPractice(RecipeBook.REPLANTING));
	}

	@Test
	void hearsayStaysHearsayUntilSeen() {
		RecipeBook book = new RecipeBook();
		assertTrue(book.hearPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("told", "Iris", UUID.randomUUID(), 10)));
		assertFalse(book.hearPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("told", "Bram", UUID.randomUUID(), 20)),
				"hearing it twice isn't news");
		assertFalse(book.knowsPractice(RecipeBook.REPLANTING));
		assertTrue(book.learnPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("made", "", null, 30)));
		assertTrue(book.knowsPractice(RecipeBook.REPLANTING));
		assertFalse(book.heardOfPractice(RecipeBook.REPLANTING), "seen it now, no longer just heard");
		assertFalse(book.hearPractice(RecipeBook.REPLANTING, new RecipeBook.Learned("told", "Vesna", UUID.randomUUID(), 40)));
	}
}
