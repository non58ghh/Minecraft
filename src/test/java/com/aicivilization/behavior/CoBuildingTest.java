package com.aicivilization.behavior;

import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.RelationshipData;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoBuildingTest {

	private static AgentMind agent(String name) {
		return new AgentMind(new Identity(UUID.randomUUID(), name, 0), new Personality(0.5, 0.5, 0.7, 0.5),
				new Needs(0.8, 0.5, 0.5, 0.2));
	}

	private static void trustEachOther(AgentMind a, AgentMind b, double trust) {
		a.relationships().restore(b.identity().id(), new RelationshipData(0.3, trust, 0, 0));
		b.relationships().restore(a.identity().id(), new RelationshipData(0.3, trust, 0, 0));
	}

	@Test
	void homelessFriendsAgreeToBuildTogether() {
		AgentMind iris = agent("Iris");
		AgentMind marcus = agent("Marcus");
		trustEachOther(iris, marcus, 0.4);
		assertTrue(CoBuilding.talk(iris, marcus, 100, 0.1, new EventLog()));
		assertTrue(iris.project().isPresent());
		assertEquals(marcus.identity().id(), iris.project().get().partner());
		assertEquals(iris.identity().id(), marcus.project().get().partner());
		assertFalse(iris.project().get().siteKnown());
	}

	@Test
	void strangersDont() {
		AgentMind iris = agent("Iris");
		AgentMind marcus = agent("Marcus");
		trustEachOther(iris, marcus, 0.1);
		assertFalse(CoBuilding.talk(iris, marcus, 100, 0.1, new EventLog()));
		assertTrue(iris.project().isEmpty());
	}

	@Test
	void aHouseAlreadyGoingUpBecomesTheSharedOneAndBothKnowWhere() {
		AgentMind iris = agent("Iris");
		AgentMind marcus = agent("Marcus");
		trustEachOther(iris, marcus, 0.4);
		iris.setBuildingSite(new Home(10, 64, 20, Design.hut(), 50));
		iris.noteBuilding(true);
		assertTrue(CoBuilding.talk(iris, marcus, 100, 0.1, new EventLog()));
		assertTrue(marcus.project().get().siteKnown());
		assertEquals(10, marcus.project().get().x());
	}

	@Test
	void theOneWhoFoundTheSiteTellsTheOtherWhenTheyMeet() {
		AgentMind iris = agent("Iris");
		AgentMind marcus = agent("Marcus");
		trustEachOther(iris, marcus, 0.4);
		CoBuilding.talk(iris, marcus, 100, 0.1, new EventLog());
		iris.setProject(iris.project().get().at(5, 70, 5));
		assertFalse(marcus.project().get().siteKnown());
		assertTrue(CoBuilding.talk(iris, marcus, 200, 0.9, new EventLog()));
		assertTrue(marcus.project().get().siteKnown());
	}
}
