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
	void prefersItsOwnDesignUnlessAClosefriendsAppealsMore() {
		AgentMind iris = settled();
		assertEquals("hut", iris.designToBuild().design().id());
		UUID friend = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		Design a = DesignGenerator.build(5, 5, 2, false, false, 0);
		Design b = DesignGenerator.build(5, 5, 3, false, false, 0);
		iris.learnDesign(new KnownDesign(new Design("a", "a", a.layers()), "saw", "Stranger", stranger, 20));
		assertEquals("a", iris.designToBuild().design().id(), "anything beats the hut");
		iris.learnDesign(new KnownDesign(new Design("c", "c", a.layers()), "designed", "", null, 30));
		assertEquals("c", iris.designToBuild().design().id(), "its own idea beats a stranger's");
		iris.relationships().restore(friend, new RelationshipData(0.6, 0.9, 0, 0));
		iris.learnDesign(new KnownDesign(new Design("b", "b", b.layers()), "told", "Friend", friend, 10));
		assertEquals("b", iris.designToBuild().design().id(), "a close, trusted friend's home can win out");
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

	@Test
	void oreInSightDrawsAContentAgentToDig() {
		AgentMind quill = new AgentMind(new Identity(UUID.randomUUID(), "Quill", 0),
				new Personality(0.5, 0.5, 0.5, 0.3), new Needs(0.8, 1.0, 0.9, 1.0));
		Set<IntentType> choices = EnumSet.of(IntentType.GATHER_MATERIALS, IntentType.REST, IntentType.IDLE);
		assertNotEquals(IntentType.GATHER_MATERIALS, quill.decide(100, choices).chosen());
		quill.noteMineable(true);
		assertEquals(IntentType.GATHER_MATERIALS, quill.decide(101, choices).chosen());
	}
}
