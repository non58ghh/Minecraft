package com.aicivilization.mind;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Living through a night attack makes home pull harder after dark; hearing of it, less; the bold, less again. */
class DarkWarinessTest {

	private static AgentMind agent(double risk) {
		AgentMind m = new AgentMind(new Identity(UUID.randomUUID(), "Orrin", 0), new Personality(0.5, risk, 0.9, 0.5),
				new Needs(0.8, 0.8, 0.2, 0.8));
		m.setHome(new Home(0, 64, 0, Design.hut(), 10));
		m.noteSurroundings(true, false);
		return m;
	}

	private static double score(AgentMind m, IntentType type) {
		return m.decide(100, EnumSet.of(IntentType.GO_HOME, IntentType.SOCIALIZE)).candidates().stream()
				.filter(c -> c.intent() == type).findFirst().orElseThrow().score();
	}

	@Test
	void nobodyStartsOutWary() {
		assertEquals(0.0, agent(0.5).darkWariness());
	}

	@Test
	void havingLivedThroughItHomeWinsAtNight() {
		AgentMind m = agent(0.5);
		double before = score(m, IntentType.GO_HOME) - score(m, IntentType.SOCIALIZE);
		m.recipeBook().learnPractice(RecipeBook.WARY_OF_THE_DARK, new RecipeBook.Learned("made", "", null, 50));
		double after = score(m, IntentType.GO_HOME) - score(m, IntentType.SOCIALIZE);
		assertTrue(after > before + 0.5, "home pulls harder and wandering off less");
	}

	@Test
	void hearsayCountsForLessAndTheBoldCareLess() {
		AgentMind heard = agent(0.5);
		heard.recipeBook().hearPractice(RecipeBook.WARY_OF_THE_DARK, new RecipeBook.Learned("told", "Iris", UUID.randomUUID(), 1));
		AgentMind lived = agent(0.5);
		lived.recipeBook().learnPractice(RecipeBook.WARY_OF_THE_DARK, new RecipeBook.Learned("made", "", null, 1));
		AgentMind bold = agent(1.0);
		bold.recipeBook().learnPractice(RecipeBook.WARY_OF_THE_DARK, new RecipeBook.Learned("made", "", null, 1));
		assertTrue(heard.darkWariness() < lived.darkWariness());
		assertTrue(bold.darkWariness() < lived.darkWariness());
	}

	@Test
	void byDayItChangesNothing() {
		AgentMind m = agent(0.5);
		m.noteSurroundings(false, false);
		double before = score(m, IntentType.SOCIALIZE);
		m.recipeBook().learnPractice(RecipeBook.WARY_OF_THE_DARK, new RecipeBook.Learned("made", "", null, 50));
		assertEquals(before, score(m, IntentType.SOCIALIZE), 1e-9);
	}
}
