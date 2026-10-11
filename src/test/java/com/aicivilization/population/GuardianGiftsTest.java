package com.aicivilization.population;

import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Possession;
import com.aicivilization.reasoning.JournalBrief;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a Guardian's support brings an agent: a parcel a game day, and a journal from its own memories. */
class GuardianGiftsTest {

	private static AgentMind iris() {
		return new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0), new Personality(0.5, 0.3, 0.7, 0.5),
				new Needs(0.4, 0.8, 0.5, 0.5));
	}

	private static int count(AgentMind m, String item) {
		return m.possessions().stream().filter(p -> p.itemId().equals(item)).mapToInt(Possession::quantity).sum();
	}

	@Test
	void aParcelArrivesOnceAGameDayAndIsRemembered() {
		AgentMind iris = iris();
		SupplyDrops drops = new SupplyDrops();
		UUID id = iris.identity().id();
		assertTrue(drops.due(id, 100), "the first parcel comes straight away");
		EventLog log = new EventLog();
		SupplyDrops.deliver(iris, "Moss Keeper", 100, log);
		assertEquals(3, count(iris, "minecraft:bread"));
		assertEquals(2, count(iris, "minecraft:apple"));
		assertTrue(iris.memories().all().stream().anyMatch(m -> m.description().contains("from my Guardian, Moss Keeper")));
		assertEquals("A supply parcel from Moss Keeper reached Iris: 3 bread and 2 apple.", log.all().get(0).summary());
	}

	@Test
	void theJournalIsWrittenOnlyFromWhatTheyRememberSinceTheLastEntry() {
		AgentMind iris = iris();
		iris.perceive(1000, "I planted wheat by the river.", 0.6, java.util.Set.of());
		iris.perceive(30_000, "Bram shared bread with me.", 0.7, java.util.Set.of());
		JournalBrief brief = JournalBrief.of(iris, 5000, 30_100);
		String p = brief.toPrompt();
		assertTrue(p.contains("Bram shared bread with me."));
		assertFalse(p.contains("planted wheat"), "older than the last entry");
		assertTrue(p.contains("You are Iris."));
		assertTrue(brief.parts().system().contains("{\"entry\""));
		assertFalse(brief.parts().system().contains("Iris"), "the fixed part names nobody");
	}
}
