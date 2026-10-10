package com.aicivilization.observer;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.story.Story;
import com.aicivilization.story.StoryGrouper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoriesJsonTest {

	private static final UUID THESSALY = UUID.randomUUID();
	private static final UUID BRAM = UUID.randomUUID();
	private static final UUID IRIS = UUID.randomUUID();

	@Test
	void storiesComeNewestFirstWithWriteUpRecordAndRoutine() {
		EventLog log = new EventLog();
		log.append(258_288, EventType.MILESTONE, List.of(THESSALY), "Thessaly gave up on making oak log: they couldn't get 20 oak log.", List.of());
		log.append(258_300, EventType.ACTION, List.of(IRIS), "Iris harvested wheat.", List.of());
		log.append(259_571, EventType.MILESTONE, List.of(THESSALY, BRAM), "Thessaly and Bram agreed to build a daring lookout tower together.", List.of());
		log.append(265_000, EventType.REASONING_RESULT, List.of(IRIS), "Iris concluded: wants to hunt sheep tomorrow", List.of());
		StoryGrouper g = new StoryGrouper();
		Map<String, UUID> index = Map.of("Thessaly", THESSALY, "Bram", BRAM, "Iris", IRIS);
		log.all().forEach(e -> g.accept(e, index));
		Story tower = g.stories().get(0);
		tower.written("Thessaly gives up on oak and finds a partner", "Thessaly gave up, then agreed with Bram.", "A tower agreed on.", 2);

		Map<UUID, String> names = Map.of(THESSALY, "Thessaly", BRAM, "Bram", IRIS, "Iris");
		JsonObject root = ObserverJson.stories(g, log::byId, id -> names.getOrDefault(id, "unknown"), 10);
		JsonArray stories = root.getAsJsonArray("stories");
		assertEquals(2, stories.size());
		JsonObject newest = stories.get(0).getAsJsonObject();
		assertFalse(newest.get("written").getAsBoolean());
		assertFalse(newest.get("worthWriting").getAsBoolean(), "one thought alone is a line, not a story");
		JsonObject written = stories.get(1).getAsJsonObject();
		assertTrue(written.get("written").getAsBoolean());
		assertEquals("A tower agreed on.", written.get("stands").getAsString());
		assertEquals(2, written.getAsJsonArray("record").size());
		assertEquals("Bram", written.getAsJsonArray("people").get(1).getAsJsonObject().get("name").getAsString());

		JsonObject day10 = root.getAsJsonArray("routine").get(0).getAsJsonObject();
		assertEquals(10, day10.get("day").getAsLong());
		assertEquals(1, day10.get("total").getAsInt());

		JsonObject compact = ObserverJson.stories(g, log::byId, id -> names.getOrDefault(id, "unknown"), 10, true);
		JsonObject event = compact.getAsJsonArray("stories").get(1).getAsJsonObject().getAsJsonArray("record").get(0).getAsJsonObject();
		assertFalse(event.has("causes"));
	}
}
