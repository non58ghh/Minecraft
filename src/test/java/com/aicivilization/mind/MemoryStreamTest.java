package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStreamTest {

	@Test
	void everyAddedMemoryCarriesTheCorrectProvenance() {
		MemoryStream memories = new MemoryStream();
		MemoryEntry perceived = memories.addPerceived(0, "saw a tree", 0.2, Set.of());
		MemoryEntry inferred = memories.addInferred(1, "trees might mean a forest", 0.3, Set.of(), perceived.id());
		MemoryEntry told = memories.addTold(2, "Marcus told me: iron is east", 0.4, Set.of(), java.util.UUID.randomUUID(), 7);

		assertInstanceOf(Provenance.Perceived.class, perceived.provenance());
		assertInstanceOf(Provenance.Inferred.class, inferred.provenance());
		assertEquals(perceived.id(), ((Provenance.Inferred) inferred.provenance()).sourceMemoryId());
		assertInstanceOf(Provenance.Told.class, told.provenance());
		assertEquals(7L, ((Provenance.Told) told.provenance()).tellerMemoryId());
	}

	@Test
	void retrievalRanksHighImportanceRecentMemoriesFirst() {
		MemoryStream memories = new MemoryStream();
		memories.addPerceived(0, "trivial, old", 0.05, Set.of());
		MemoryEntry important = memories.addPerceived(100, "very important", 0.95, Set.of());

		List<MemoryEntry> retrieved = memories.retrieve(100, 1);

		assertEquals(1, retrieved.size());
		assertEquals(important.id(), retrieved.get(0).id());
	}

	@Test
	void toldMemoriesAreBrandNewEntriesNotSharedObjects() {
		MemoryStream speakerMemories = new MemoryStream();
		MemoryEntry original = speakerMemories.addPerceived(0, "found iron east of town", 0.7, Set.of());

		MemoryStream listenerMemories = new MemoryStream();
		MemoryEntry told = listenerMemories.addTold(10, "Marcus told me: found iron east of town", 0.6,
				Set.of(), java.util.UUID.randomUUID(), original.id());

		assertTrue(told != original, "told memory must be a brand-new object, not the teller's memory itself");
		assertTrue(listenerMemories.all().size() == 1 && speakerMemories.all().size() == 1,
				"the two streams must not share entries");
		assertEquals(original.id(), ((Provenance.Told) told.provenance()).tellerMemoryId(),
				"the told memory must still point back at the teller's specific source memory");
	}
}
