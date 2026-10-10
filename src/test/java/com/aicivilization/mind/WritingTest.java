package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading is its own way of knowing, traced to the writer; writing is chosen only with something to say. */
class WritingTest {

	private static AgentMind agent(double curiosity, double sociability) {
		return new AgentMind(new Identity(UUID.randomUUID(), "Linnea", 0), new Personality(curiosity, 0.5, sociability, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void nobodyStartsOutKnowingWriting() {
		RecipeBook book = new RecipeBook();
		assertFalse(book.knowsPractice(RecipeBook.WRITING));
		assertFalse(book.heardOfPractice(RecipeBook.WRITING));
	}

	@Test
	void whatsReadIsTracedToTheWritersMemory() {
		AgentMind reader = agent(0.5, 0.5);
		UUID author = UUID.randomUUID();
		MemoryEntry read = reader.readWriting(100, 7, "Trees / 48 blocks / north / - Iris d3", author, 42, 0.55).orElseThrow();
		assertEquals(new Provenance.Read(7, author, 42), read.provenance());
		assertTrue(read.description().contains("Trees / 48 blocks / north"));
		assertEquals(Set.of(author), read.participants());
	}

	@Test
	void aSignIsReadOnce() {
		AgentMind reader = agent(0.5, 0.5);
		assertTrue(reader.readWriting(100, 7, "Beware", null, -1, 0.5).isPresent());
		assertTrue(reader.readWriting(200, 7, "Beware", null, -1, 0.5).isEmpty());
		assertTrue(reader.readWriting(200, 8, "Beware", null, -1, 0.5).isPresent(), "different writing");
		assertEquals(Set.of(7L, 8L), reader.readDocuments());
	}

	@Test
	void aPlayersSignHasNoKnownAuthor() {
		MemoryEntry read = agent(0.5, 0.5).readWriting(100, 3, "forest 60 north", null, -1, 0.5).orElseThrow();
		assertEquals(new Provenance.Read(3, null, -1), read.provenance());
		assertTrue(read.participants().isEmpty());
	}

	@Test
	void withSomethingWorthSayingTheSociableWrite() {
		AgentMind linnea = agent(0.5, 0.9);
		linnea.recipeBook().learnPractice(RecipeBook.WRITING, new RecipeBook.Learned("saw", "", null, 1));
		linnea.noteSomethingToWrite(0.7, false);
		assertEquals(IntentType.WRITE_SIGN,
				linnea.decide(100, EnumSet.of(IntentType.WRITE_SIGN, IntentType.REST, IntentType.IDLE)).chosen());
	}

	@Test
	void trulyStarvingItWritesNothing() {
		AgentMind linnea = new AgentMind(new Identity(UUID.randomUUID(), "Linnea", 0), new Personality(0.5, 0.5, 0.9, 0.5),
				new Needs(0.05, 0.8, 0.8, 0.8));
		linnea.noteSomethingToWrite(0.7, false);
		assertNotEquals(IntentType.WRITE_SIGN,
				linnea.decide(100, EnumSet.of(IntentType.WRITE_SIGN, IntentType.FORAGE_FOOD, IntentType.IDLE)).chosen());
	}

	@Test
	void neverHavingSeenItDoneHoldsTheIncuriousBack() {
		AgentMind dull = agent(0.0, 0.5);
		dull.noteSomethingToWrite(0.7, true);
		double untried = dull.decide(100, EnumSet.of(IntentType.WRITE_SIGN)).candidates().get(0).factors().get("never seen it done");
		assertTrue(untried < 0);
	}
}
