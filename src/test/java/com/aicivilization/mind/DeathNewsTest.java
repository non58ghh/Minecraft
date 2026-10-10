package com.aicivilization.mind;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathNewsTest {

	private static AgentMind mind(String name) {
		return new AgentMind(new Identity(UUID.randomUUID(), name, 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void aFriendsDeathIsFeltAndAStrangersBarely() {
		AgentMind iris = mind("Iris");
		UUID bram = UUID.randomUUID(), stranger = UUID.randomUUID();
		iris.relationships().with(bram).recordConversation(0, 0.8, 0.5);
		double social = iris.needs().social();
		assertTrue(iris.learnOfDeath(10, bram, "Bram", "was killed by a zombie", AgentMind.DeathNews.SAW, null, null));
		assertTrue(iris.needs().social() < social - 0.1);
		assertTrue(iris.memories().retrieve(10, 1).get(0).description().startsWith("I saw Bram die"));
		double after = iris.needs().social();
		iris.learnOfDeath(20, stranger, "Vesna", "starved to death", AgentMind.DeathNews.FOUND, null, null);
		assertEquals(after, iris.needs().social(), 1e-9);
		assertFalse(iris.learnOfDeath(30, bram, "Bram", "was killed by a zombie", AgentMind.DeathNews.SAW, null, null),
				"known already");
	}

	@Test
	void toldOfADeathItsHearsayFromTheTeller() {
		AgentMind iris = mind("Iris");
		UUID orrin = UUID.randomUUID(), bram = UUID.randomUUID();
		iris.learnOfDeath(10, bram, "Bram", "was killed by a zombie", AgentMind.DeathNews.TOLD, orrin, "Orrin");
		MemoryEntry m = iris.memories().retrieve(10, 1).get(0);
		assertEquals("Orrin told me Bram was killed by a zombie.", m.description());
		assertTrue(m.provenance() instanceof Provenance.Told);
		assertTrue(iris.knowsDead(bram));
	}

	@Test
	void plansWithTheDeadAreSetDown() {
		AgentMind iris = mind("Iris");
		UUID bram = UUID.randomUUID();
		Goal g = iris.addGoal(0, "Find Bram and trade wood", 0.6, IntentType.SOCIALIZE);
		iris.learnOfDeath(10, bram, "Bram", "drowned", AgentMind.DeathNews.TOLD, UUID.randomUUID(), "Orrin");
		assertTrue(iris.goals().stream().noneMatch(x -> x.id() == g.id() && x.active()));
	}

	@Test
	void aChildGrowsUpOverTwelveDays() {
		AgentMind child = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 1000), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		child.setParents(List.of(UUID.randomUUID(), UUID.randomUUID()));
		assertTrue(child.isChild(1000 + 24000));
		assertEquals(0.5, child.growth(1000 + AgentMind.GROWING_UP_TICKS / 2), 1e-9);
		assertFalse(child.isChild(1000 + AgentMind.GROWING_UP_TICKS));
		assertFalse(mind("Founder").isChild(0), "founders and wanderers arrive grown");
	}
}
