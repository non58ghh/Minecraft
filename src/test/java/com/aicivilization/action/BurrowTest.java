package com.aicivilization.action;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.RecipeBook;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurrowTest {

	private static AgentMind mind(double curiosity) {
		return new AgentMind(new Identity(UUID.randomUUID(), "Petra", 0), new Personality(curiosity, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void onlyTheCuriousTryItUnprompted() {
		assertFalse(Burrow.wouldTry(mind(0.0), 0.01));
		assertTrue(Burrow.wouldTry(mind(1.0), 0.5));
		assertFalse(Burrow.wouldTry(mind(1.0), 0.7));
	}

	@Test
	void hearingOfItMakesItLikelyAndKnowingItCertain() {
		AgentMind m = mind(0.0);
		m.recipeBook().hearPractice(RecipeBook.BURROWING, new RecipeBook.Learned("told", "Orrin", UUID.randomUUID(), 0));
		assertTrue(Burrow.wouldTry(m, 0.6));
		assertFalse(Burrow.wouldTry(m, 0.8));
		m.recipeBook().learnPractice(RecipeBook.BURROWING, new RecipeBook.Learned("made", "", null, 0));
		assertTrue(Burrow.wouldTry(m, 0.99));
	}
}
