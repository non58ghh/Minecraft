package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Provenance;
import com.aicivilization.mind.RecipeBook;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What was read, from whom, and that it was read at all, survive a restart. */
class ReadingNbtTest {

	@Test
	void readingSurvivesSaveAndLoad() {
		AgentMind mind = new AgentMind(new Identity(UUID.randomUUID(), "Ilse", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID author = UUID.randomUUID();
		mind.readWriting(10, 4, "Trees / 48 blocks / north", author, 9, 0.55);
		mind.readWriting(20, 5, "forest 60 north", null, -1, 0.4);
		mind.recipeBook().learnPractice(RecipeBook.WRITING, new RecipeBook.Learned("saw", "Iris", author, 10));

		AgentMind loaded = AgentMindNbt.read(AgentMindNbt.write(mind));

		assertEquals(Set.of(4L, 5L), loaded.readDocuments());
		assertTrue(loaded.recipeBook().knowsPractice(RecipeBook.WRITING));
		assertTrue(loaded.memories().all().stream().map(MemoryEntry::provenance).toList()
				.containsAll(java.util.List.of(new Provenance.Read(4, author, 9), new Provenance.Read(5, null, -1))));
	}
}
