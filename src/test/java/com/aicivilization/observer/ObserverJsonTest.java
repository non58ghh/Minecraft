package com.aicivilization.observer;

import com.aicivilization.events.Cause;
import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Provenance;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserverJsonTest {

	private static AgentMind newMind(String name) {
		return new AgentMind(
				new Identity(UUID.randomUUID(), name, 0),
				new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void summaryReportsNeedsCurrentIntentAndMissingBody() {
		AgentMind mind = newMind("Elias");
		mind.needs().adjustFood(-0.79);
		mind.decide(100, EnumSet.of(IntentType.FORAGE_FOOD, IntentType.IDLE));

		JsonObject json = ObserverJson.agentSummary(mind, null, 150);

		assertEquals("Elias", json.get("name").getAsString());
		assertEquals("FORAGE_FOOD", json.get("currentIntent").getAsString());
		assertEquals(100, json.get("lastDecisionTick").getAsLong());
		assertTrue(json.get("crisis").getAsBoolean());
		assertTrue(json.get("position").isJsonNull(), "an unloaded agent has no position");
		assertEquals(0.01, json.getAsJsonObject("needs").get("food").getAsDouble(), 1e-9);
	}

	@Test
	void detailResolvesNamesThroughToldProvenanceAndKeepsDecisionFactors() {
		AgentMind teller = newMind("Lyra");
		AgentMind listener = newMind("Bram");
		MemoryEntry source = teller.perceive(10, "berries by the river", 0.7, Set.of());
		listener.receiveTold(20, teller.identity().id(), "Lyra", source);
		listener.decide(30, EnumSet.of(IntentType.FORAGE_FOOD, IntentType.SOCIALIZE));
		Map<UUID, String> names = Map.of(teller.identity().id(), "Lyra", listener.identity().id(), "Bram");
		Function<UUID, String> lookup = id -> names.getOrDefault(id, "unknown");

		JsonObject json = ObserverJson.agentDetail(listener,
				new ObserverJson.Position("minecraft:overworld", 1.23, 64, -5.67), 40, lookup, List.of());

		JsonObject memory = json.getAsJsonArray("memories").get(0).getAsJsonObject();
		JsonObject provenance = memory.getAsJsonObject("provenance");
		assertEquals("TOLD", provenance.get("type").getAsString());
		assertEquals("Lyra", provenance.get("tellerName").getAsString());
		assertEquals(source.id(), provenance.get("tellerMemoryId").getAsLong());

		JsonObject relationship = json.getAsJsonArray("relationships").get(0).getAsJsonObject();
		assertEquals("Lyra", relationship.get("name").getAsString());
		assertEquals(1, relationship.get("thingsLearned").getAsInt());

		JsonObject decision = json.getAsJsonArray("decisions").get(0).getAsJsonObject();
		JsonArray candidates = decision.getAsJsonArray("candidates");
		assertEquals(2, candidates.size());
		assertFalse(candidates.get(0).getAsJsonObject().getAsJsonObject("factors").isEmpty());

		assertEquals(1.2, json.getAsJsonObject("position").get("x").getAsDouble(), 1e-9);
	}

	@Test
	void detailListsNewestMemoriesFirstAndCapsThem() {
		AgentMind mind = newMind("Wren");
		for (int i = 0; i < ObserverJson.DETAIL_MEMORY_LIMIT + 5; i++) {
			mind.perceive(i, "thing " + i, 0.5, Set.of());
		}

		JsonArray memories = ObserverJson.agentDetail(mind, null, 100, id -> "x", List.of()).getAsJsonArray("memories");

		assertEquals(ObserverJson.DETAIL_MEMORY_LIMIT, memories.size());
		assertEquals("thing " + (ObserverJson.DETAIL_MEMORY_LIMIT + 4),
				memories.get(0).getAsJsonObject().get("description").getAsString());
	}

	@Test
	void eventJsonCarriesCausesAndSubjectNames() {
		UUID a = UUID.randomUUID();
		SimEvent event = new SimEvent(7, 500, EventType.CONVERSATION, List.of(a), "Iris talked",
				List.of(Cause.event(3, "met earlier"), Cause.memory(12, "remembered food")));

		JsonObject json = ObserverJson.event(event, id -> id.equals(a) ? "Iris" : "unknown");

		assertEquals(7, json.get("id").getAsLong());
		assertEquals("Iris", json.getAsJsonArray("subjects").get(0).getAsJsonObject().get("name").getAsString());
		JsonArray causes = json.getAsJsonArray("causes");
		assertEquals("EVENT", causes.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("3", causes.get(0).getAsJsonObject().get("sourceId").getAsString());
		// Round-trips as valid JSON text.
		assertEquals(json, JsonParser.parseString(json.toString()));
	}

	@Test
	void perceivedAndInferredProvenanceAreLabelled() {
		assertEquals("PERCEIVED", ObserverJson.provenance(new Provenance.Perceived(), id -> "").get("type").getAsString());
		JsonObject inferred = ObserverJson.provenance(new Provenance.Inferred(9), id -> "");
		assertEquals("INFERRED", inferred.get("type").getAsString());
		assertEquals(9, inferred.get("sourceMemoryId").getAsLong());
	}
}
