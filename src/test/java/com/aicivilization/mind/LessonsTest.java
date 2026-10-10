package com.aicivilization.mind;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonsTest {

	@Test
	void theThirdOfTheSameFailureWithinAFewDaysIsNoticedOnce() {
		Lessons l = new Lessons();
		assertTrue(l.note(Lessons.Failure.ATTACKED, 0, 0, 13000, true).isEmpty());
		assertTrue(l.note(Lessons.Failure.ATTACKED, 10, 5, 13000 + Lessons.DAY, true).isEmpty());
		Optional<String> lesson = l.note(Lessons.Failure.ATTACKED, -8, 4, 13000 + 2 * Lessons.DAY, true);
		assertEquals(Optional.of("I've been attacked by monsters three times in the last 2 days, every time at night, "
				+ "and always around the same place."), lesson);
		assertTrue(l.note(Lessons.Failure.ATTACKED, 0, 0, 14000 + 2 * Lessons.DAY + 2000, true).isEmpty(),
				"noticed already, not again so soon");
	}

	@Test
	void theSameFailureAMomentLaterIsTheSameFailure() {
		Lessons l = new Lessons();
		l.note(Lessons.Failure.STUCK, 0, 0, 0, false);
		l.note(Lessons.Failure.STUCK, 0, 0, 100, false);
		assertTrue(l.note(Lessons.Failure.STUCK, 0, 0, 200, false).isEmpty());
		assertEquals(1, l.misses().size());
	}

	@Test
	void oldFailuresAndOtherKindsDontMakeAPattern() {
		Lessons l = new Lessons();
		l.note(Lessons.Failure.NO_FOOD, 0, 0, 0, false);
		l.note(Lessons.Failure.NO_FOOD, 0, 0, 5000, false);
		l.note(Lessons.Failure.NO_TREES, 0, 0, 10000, false);
		assertTrue(l.note(Lessons.Failure.NO_FOOD, 0, 0, 4 * Lessons.DAY, false).isEmpty());
	}

	@Test
	void mixedTimesAndPlacesAreLeftUnsaid() {
		Lessons l = new Lessons();
		l.note(Lessons.Failure.NO_TREES, 0, 0, 0, false);
		l.note(Lessons.Failure.NO_TREES, 200, 0, 15000, true);
		assertEquals(Optional.of("I've set out looking for trees and found none three times in the last day."),
				l.note(Lessons.Failure.NO_TREES, 0, 300, 20000, false));
	}

	@Test
	void aPatternBecomesAMemoryOfItsOwnWorkingOut() {
		AgentMind m = new AgentMind(new Identity(UUID.randomUUID(), "Wilder", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		for (int i = 0; i < 3; i++) {
			m.noteFailure(Lessons.Failure.NO_FOOD, 0, 0, i * 3000L, false);
		}
		MemoryEntry lesson = m.memories().retrieve(9000, 1).get(0);
		assertTrue(lesson.description().startsWith("I've gone looking for food and come back with nothing three times"));
		assertTrue(lesson.provenance() instanceof Provenance.Inferred);
	}

	@Test
	void findingNothingWhereToldCostsTheTellerSomeTrust() {
		AgentMind m = new AgentMind(new Identity(UUID.randomUUID(), "Wilder", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		UUID mabry = UUID.randomUUID();
		assertTrue(m.hearOfPlace(100, Places.Kind.WOODS, 50, 64, 0, 90, mabry, "Mabry",
				"Mabry told me there are trees about 50 blocks east of where we talked."));
		assertTrue(m.memories().retrieve(100, 1).get(0).provenance() instanceof Provenance.Told);
		assertFalse(m.hearOfPlace(110, Places.Kind.WOODS, 52, 64, 0, 90, mabry, "Mabry", "again"), "already heard");
		double trust = m.relationships().with(mabry).trust();
		var heard = m.places().forget(Places.Kind.WOODS, 50, 64, 0, 16);
		assertEquals(1, heard.size());
		m.foundNothingWhereTold(2000, heard.get(0), "trees", false);
		assertTrue(m.relationships().with(mabry).trust() < trust);
		assertEquals("Mabry told me there were trees over this way, but I found none.",
				m.memories().retrieve(2000, 1).get(0).description());
	}
}
