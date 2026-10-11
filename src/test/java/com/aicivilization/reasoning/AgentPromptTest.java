package com.aicivilization.reasoning;

import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Provenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptTest {

	private static long id = 1;

	private static MemoryEntry m(long tick, String text) {
		return new MemoryEntry(id++, tick, text, 0.3, Set.of(), new Provenance.Perceived());
	}

	static AgentContext linnea() {
		long tick = 6 * 24000 + 10_500;
		return new AgentContext("Linnea", tick, 0.41, 0.33, 0.78, 0.29, 0.22, 0.28, 0.0, 0.0,
				List.of("Day 4: I met a person named hughjohnson6594.", "Today: tended my field ×4, harvested wheat ×2."),
				List.of("find hughjohnson6594 and share bread"), List.of("6 bread", "14 wheat seeds"),
				List.of("I need to take my social more seriously."),
				"none yet. You have a cozy social cottage in mind, needing about 52 blocks; you carry wood for about 0.",
				List.of("crafting table", "bread"),
				new AgentContext.Situation(6, 10_500, "Out in the open.", List.of(), 10),
				6, List.of("Ilse: someone you like. Last seen on day 5."),
				List.of("Find hughjohnson6594 and share bread. Since day 5; not started."), List.of());
	}

	@Test
	void timeOfDayWarnsWhenNightIsNear() {
		assertEquals("Day 6, early evening. Night falls in about 2 minutes, and monsters come out in the dark.",
				AgentContext.timeLine(6, 10_500));
		assertTrue(AgentContext.timeLine(6, 3000).contains("morning"));
		assertTrue(AgentContext.timeLine(6, 18_000).contains("monsters are out and will attack you"));
	}

	@Test
	void beforeDawnSaysDawnEndsTheDanger() {
		assertEquals("Day 6, just before dawn. It's dark, and monsters are out and will attack you. Dawn comes in about 1 minute; "
				+ "at dawn the danger ends, as daylight burns zombies and skeletons.", AgentContext.timeLine(6, 23_200));
	}

	@Test
	void personalityAndNeedsAreWords() {
		assertEquals("Warm and outgoing; cautious; middling curiosity; not very ambitious.",
				AgentContext.personalityWords(0.41, 0.33, 0.78, 0.29));
		List<String> needs = AgentContext.needLines(0.22, 0.28, 0.0, 0.0, 6, true);
		assertEquals("Food: very hungry, but you're carrying about 6 meals' worth.", needs.get(0));
		assertEquals("Safety: you feel in danger. You have no home.", needs.get(1));
	}

	@Test
	void choresAreFoldedAndRepeatsCounted() {
		long now = 6 * 24000 + 10_000;
		List<String> lines = ReasoningScheduler.memoryLines(List.of(
				m(5 * 24000 + 100, "Ilse told me: I imagined a cozy cottage."),
				m(6 * 24000 + 100, "I tended my field."),
				m(6 * 24000 + 200, "I tended my field."),
				m(6 * 24000 + 300, "I harvested wheat."),
				m(6 * 24000 + 400, "I chopped down an oak tree and took 7 logs."),
				m(6 * 24000 + 500, "I met a person named hughjohnson6594."),
				m(6 * 24000 + 600, "I met a person named hughjohnson6594.")), now);
		assertEquals(List.of(
				"Yesterday: Ilse told me: I imagined a cozy cottage.",
				"Today: I met a person named hughjohnson6594. (2 times)",
				"Today: tended my field ×2, harvested wheat, chopped down an oak tree."), lines);
	}

	@Test
	void setbacksAreRecentFailuresOnly() {
		long now = 6 * 24000;
		List<String> s = ReasoningScheduler.setbacks(List.of(
				m(now - 30_000, "I gave up on making oak log; I couldn't find oak logs."),
				m(now - 1000, "I gave up on making oak log; I couldn't find oak logs."),
				m(now - 500, "I harvested wheat.")), now);
		assertEquals(List.of("I gave up on making oak log; I couldn't find oak logs."), s);
	}

	@Test
	void thePromptAsksForTheActivityFirstAndExplainsEachOne() {
		String p = linnea().toPromptSummary();
		assertTrue(p.contains("one of 10 people in a young settlement"));
		assertTrue(p.contains("THE WORLD RIGHT NOW\nDay 6, early evening."));
		assertTrue(p.contains("Ilse: someone you like."));
		assertTrue(p.contains("SEEK_SAFETY: get away from danger, such as a monster, or hide from it where you are. It doesn't build anything."));
		assertTrue(p.contains("First choose the one activity Linnea will actually do next."));
		assertTrue(p.indexOf("\"relatedIntent\"") < p.indexOf("\"goal\""), "the activity comes first in the reply");
		assertTrue(p.contains("0.3 a passing wish, 0.6 important, 0.9 what matters most now"));
		assertFalse(p.contains("STARVING") || p.contains("starving: finding"), "needs come with context, not alarm");
		assertTrue(p.contains("Safety: you feel in danger. You have no home."));
	}
}
