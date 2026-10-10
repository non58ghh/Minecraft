package com.aicivilization.story;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.aicivilization.reasoning.StoryBrief;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryWriterTest {

	@Test
	void gameTimeReadsLikeTheObserverPage() {
		assertEquals("Day 10, 10:26 pm", StoryWriter.when(256_434));
		assertEquals("Day 10, 12:03 am", StoryWriter.when(258_065));
		assertEquals("Day 0, 6:00 am", StoryWriter.when(0));
		assertEquals("Day 1, 12:00 pm", StoryWriter.when(30_000));
	}

	@Test
	void theBriefIsTheStorysRecordInOrderWithConversationLines() {
		UUID elias = UUID.randomUUID();
		UUID vesna = UUID.randomUUID();
		EventLog log = new EventLog();
		SimEvent thought = log.append(257_182, EventType.REASONING_RESULT, List.of(elias),
				"Elias concluded: wants to reach hillside to reunite with Wilder and Thessaly", List.of());
		log.append(257_500, EventType.ACTION, List.of(vesna), "Vesna harvested wheat.", List.of());
		SimEvent talk = log.append(258_065, EventType.CONVERSATION, List.of(elias, vesna),
				"Elias and Vesna talked about Vesna's loft tower and safer building spots.", List.of(),
				List.of("Elias: If I gather the wood, would you help me try it?", "Vesna: I can do that."));
		StoryGrouper g = new StoryGrouper();
		Map<String, UUID> index = Map.of("Elias", elias, "Vesna", vesna);
		log.all().forEach(e -> g.accept(e, index));
		Story story = g.stories().get(0);
		assertEquals(List.of(thought.id(), talk.id()), story.eventIds());

		StoryBrief brief = StoryWriter.brief(story, log, Map.of(elias, "Elias", vesna, "Vesna"));
		assertEquals(List.of("Elias", "Vesna"), brief.people());
		assertEquals(4, brief.record().size());
		assertTrue(brief.record().get(0).startsWith("Day 10, 11:10 pm · Elias concluded"));
		assertEquals("    Vesna: I can do that.", brief.record().get(3));

		String prompt = brief.toPrompt();
		assertTrue(prompt.contains("Use only what the record says"));
		assertTrue(prompt.contains("Never use he, she"));
		assertTrue(prompt.contains("Vesna: I can do that."));
	}
}
