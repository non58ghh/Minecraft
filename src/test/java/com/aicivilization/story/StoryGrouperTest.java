package com.aicivilization.story;

import com.aicivilization.events.Cause;
import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Built around a real night on the live server (day 10). */
class StoryGrouperTest {

	private static final UUID IRIS = UUID.randomUUID();
	private static final UUID MABRY = UUID.randomUUID();
	private static final UUID ORRIN = UUID.randomUUID();
	private static final UUID THESSALY = UUID.randomUUID();
	private static final UUID BRAM = UUID.randomUUID();
	private static final UUID LINNEA = UUID.randomUUID();
	private static final Map<String, UUID> NAMES = Map.of("Iris", IRIS, "Mabry", MABRY, "Orrin", ORRIN,
			"Thessaly", THESSALY, "Bram", BRAM, "Linnea", LINNEA);

	private long nextId = 1;

	private SimEvent ev(long tick, EventType type, String summary, UUID... subjects) {
		return new SimEvent(nextId++, tick, type, List.of(subjects), summary, List.of());
	}

	@Test
	void routineWorkIsCountedNotStoried() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(240_000, EventType.ACTION, "Iris harvested wheat.", IRIS), NAMES);
		g.accept(ev(240_100, EventType.ACTION, "Iris harvested 3 plants of wheat.", IRIS), NAMES);
		g.accept(ev(240_200, EventType.ACTION, "Linnea tended their field.", LINNEA), NAMES);
		assertTrue(g.stories().isEmpty());
		assertEquals(2, g.routineByDay().get(10L).get(IRIS));
		assertEquals(1, g.routineByDay().get(10L).get(LINNEA));
	}

	@Test
	void bookkeepingIsLeftOut() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(1, EventType.REASONING_INVOKED, "Iris stopped to think, prompted by routine reflection.", IRIS), NAMES);
		g.accept(ev(2, EventType.REASONING_FAILED, "Iris's thinking didn't come through: the reply was empty.", IRIS), NAMES);
		g.accept(ev(3, EventType.DECISION, "Iris decided to farm.", IRIS), NAMES);
		g.accept(ev(4, EventType.REASONING_RESULT, "Iris concluded: nothing in particular this time", IRIS), NAMES);
		assertTrue(g.stories().isEmpty());
	}

	@Test
	void theWoolPlanBecomesOneStoryThroughNamesMentioned() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(256_434, EventType.TOLD, "Mabry told Orrin: I agreed with Iris to lay wool on the roof once Iris's hunt is done.",
				MABRY, ORRIN), NAMES);
		g.accept(ev(256_436, EventType.REASONING_RESULT,
				"Orrin concluded: wants to hunt sheep and gather wool with Iris tomorrow as promised", ORRIN), NAMES);
		g.accept(ev(256_520, EventType.ACTION, "Iris tended their field.", IRIS), NAMES);
		g.accept(ev(257_929, EventType.REASONING_RESULT,
				"Iris concluded: wants to hunt sheep with Mabry and Orrin tomorrow to gather wool for shelter", IRIS), NAMES);
		List<Story> stories = g.stories();
		assertEquals(1, stories.size());
		assertEquals(3, stories.get(0).eventIds().size());
		assertTrue(stories.get(0).people().containsAll(List.of(MABRY, ORRIN, IRIS)));
	}

	@Test
	void giveUpThenAgreementIsOneStoryAboutThessaly() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(257_929, EventType.REASONING_RESULT,
				"Thessaly concluded: wants to find Wilder and collaborate on gathering oak logs for shelter", THESSALY), NAMES);
		g.accept(ev(258_288, EventType.MILESTONE, "Thessaly gave up on making oak log: they couldn't get 20 oak log.", THESSALY),
				NAMES);
		g.accept(ev(259_571, EventType.MILESTONE, "Thessaly and Bram agreed to build a daring lookout tower together.",
				THESSALY, BRAM), NAMES);
		assertEquals(1, g.stories().size());
		assertTrue(g.stories().get(0).people().contains(BRAM));
	}

	@Test
	void unrelatedPeopleGetSeparateStories() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(253_308, EventType.REASONING_RESULT, "Linnea concluded: wants to find hughjohnson6594 and share bread", LINNEA),
				NAMES);
		g.accept(ev(256_434, EventType.TOLD, "Mabry told Orrin: I agreed with Iris to lay wool on the roof.", MABRY, ORRIN), NAMES);
		assertEquals(2, g.stories().size());
	}

	@Test
	void aQuietStoryClosesAndTheNextNewsStartsAnother() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(100_000, EventType.REASONING_RESULT, "Linnea concluded: wants to find friends", LINNEA), NAMES);
		g.accept(ev(100_000 + StoryGrouper.QUIET_TICKS + 1, EventType.REASONING_RESULT,
				"Linnea concluded: wants to plant a field", LINNEA), NAMES);
		assertEquals(2, g.stories().size());
		assertTrue(g.stories().get(0).closed());
		assertFalse(g.stories().get(1).closed());
	}

	@Test
	void anEventJoinsTheStoryOfTheEventThatCausedIt() {
		StoryGrouper g = new StoryGrouper();
		SimEvent talk = ev(1000, EventType.CONVERSATION, "Iris and Mabry talked about the roof.", IRIS, MABRY);
		g.accept(talk, NAMES);
		g.accept(ev(1100, EventType.REASONING_RESULT, "Bram concluded: wants to explore", BRAM), NAMES);
		SimEvent caused = new SimEvent(nextId++, 1200, EventType.ACTION, List.of(BRAM),
				"Bram gave Iris 4 bread, as they'd agreed.", List.of(Cause.event(talk.id(), "agreed then")));
		g.accept(caused, NAMES);
		Story first = g.stories().get(0);
		assertTrue(first.eventIds().contains(caused.id()));
	}

	@Test
	void oneThoughtAloneIsNotWrittenUpButAConversationIs() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(1, EventType.REASONING_RESULT, "Linnea concluded: wants to find friends", LINNEA), NAMES);
		g.accept(ev(2, EventType.CONVERSATION, "Iris and Mabry talked for a while.", IRIS, MABRY), NAMES);
		assertFalse(g.stories().get(0).worthWriting());
		assertTrue(g.stories().get(1).worthWriting());
	}

	@Test
	void theNewestFinishedStoryIsWrittenFirstAndOnlyOnceUntilItGrows() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(1000, EventType.CONVERSATION, "Iris and Mabry talked for a while.", IRIS, MABRY), NAMES);
		g.accept(ev(1100, EventType.CONVERSATION, "Linnea and Bram talked for a while.", LINNEA, BRAM), NAMES);
		assertTrue(g.nextToWrite(1200, 1200, 4, 48_000).isEmpty(), "both stories are still unfolding");
		Story next = g.nextToWrite(2400, 1200, 4, 48_000).orElseThrow();
		assertEquals(2, next.id());
		next.written("Linnea and Bram talk", "They talked.", "Friends now.", 1);
		assertEquals(1, g.nextToWrite(2400, 1200, 4, 48_000).orElseThrow().id());
		g.accept(ev(2500, EventType.REASONING_RESULT, "Bram concluded: wants to visit Linnea again", BRAM), NAMES);
		assertTrue(g.story(2).orElseThrow().needsWriting());
	}

	@Test
	void aLongThreadContinuesAsANewStory() {
		StoryGrouper g = new StoryGrouper();
		long tick = 0;
		for (int i = 0; i < StoryGrouper.MAX_EVENTS + 1; i++) {
			g.accept(ev(tick += 100, EventType.REASONING_RESULT, "Linnea concluded: wants thing " + i, LINNEA), NAMES);
		}
		assertEquals(2, g.stories().size());
		assertTrue(g.stories().get(0).closed());
	}

	@Test
	void oldStoriesAreLeftAsTheirRecord() {
		StoryGrouper g = new StoryGrouper();
		g.accept(ev(1000, EventType.CONVERSATION, "Iris and Mabry talked for a while.", IRIS, MABRY), NAMES);
		assertTrue(g.nextToWrite(60_000, 1200, 4, 48_000).isEmpty());
		assertTrue(g.nextToWrite(40_000, 1200, 4, 48_000).isPresent());
	}
}
