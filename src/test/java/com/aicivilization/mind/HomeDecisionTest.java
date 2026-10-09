package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HomeDecisionTest {

	private static final Set<IntentType> CHOICES = EnumSet.of(
			IntentType.GO_HOME, IntentType.EXPLORE, IntentType.SOCIALIZE, IntentType.REST, IntentType.IDLE);

	private static AgentMind settled() {
		AgentMind iris = new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0),
				new Personality(0.5, 0.5, 0.5, 0.5), new Needs(0.8, 0.8, 0.8, 0.8));
		iris.setHome(new Home(0, 64, 0, Design.hut(), 10));
		return iris;
	}

	@Test
	void atNightAnAgentAwayFromHomeHeadsBack() {
		AgentMind iris = settled();
		iris.noteSurroundings(true, false);
		assertEquals(IntentType.GO_HOME, iris.decide(100, CHOICES).chosen());
	}

	@Test
	void atNightAtHomeItSleeps() {
		AgentMind iris = settled();
		iris.noteSurroundings(true, true);
		assertEquals(IntentType.REST, iris.decide(100, EnumSet.of(IntentType.EXPLORE, IntentType.SOCIALIZE,
				IntentType.REST, IntentType.IDLE)).chosen());
	}

	@Test
	void byDayItGoesAboutItsBusiness() {
		AgentMind iris = settled();
		iris.noteSurroundings(false, false);
		assertNotEquals(IntentType.GO_HOME, iris.decide(100, CHOICES).chosen());
	}

	@Test
	void hungerStillComesFirstAtNight() {
		AgentMind iris = new AgentMind(new Identity(UUID.randomUUID(), "Iris", 0),
				new Personality(0.5, 0.5, 0.5, 0.5), new Needs(0.0, 0.8, 0.8, 0.8));
		iris.setHome(new Home(0, 64, 0, Design.hut(), 10));
		iris.noteSurroundings(true, false);
		assertEquals(IntentType.FORAGE_FOOD, iris.decide(100, EnumSet.of(IntentType.GO_HOME, IntentType.FORAGE_FOOD)).chosen());
	}

	@Test
	void prefersItsOwnDesignThenTrustedOnesOverTheHut() {
		AgentMind iris = settled();
		assertEquals("hut", iris.designToBuild().design().id());
		UUID friend = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		iris.relationships().restore(friend, new RelationshipData(0.5, 0.9, 0, 0));
		Design a = DesignGenerator.build(5, 5, 2, false, false, 0);
		Design b = DesignGenerator.build(5, 5, 3, false, false, 0);
		iris.learnDesign(new KnownDesign(new Design("a", "a", a.layers()), "saw", "Stranger", stranger, 20));
		iris.learnDesign(new KnownDesign(new Design("b", "b", b.layers()), "told", "Friend", friend, 10));
		assertEquals("b", iris.designToBuild().design().id());
		iris.learnDesign(new KnownDesign(new Design("c", "c", a.layers()), "designed", "", null, 30));
		assertEquals("c", iris.designToBuild().design().id());
	}

	@Test
	void neverForgetsTheHutAndDoesNotLearnTwice() {
		AgentMind iris = settled();
		Design a = DesignGenerator.build(5, 5, 2, false, false, 0);
		for (int i = 0; i < 30; i++) {
			iris.learnDesign(new KnownDesign(new Design("d" + i, "d", a.layers()), "saw", "X", null, i));
		}
		assertEquals("hut", iris.knownDesigns().get(0).design().id());
		assertFalse(iris.learnDesign(new KnownDesign(new Design("d29", "d", a.layers()), "saw", "X", null, 99)));
	}
}
