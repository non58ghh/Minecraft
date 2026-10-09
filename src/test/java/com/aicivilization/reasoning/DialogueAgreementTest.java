package com.aicivilization.reasoning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueAgreementTest {

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void giftFromBGetsANamespacedItem() {
		Dialogue.Agreement a = AnthropicReasoningProvider.parseAgreement(
				json("{\"type\":\"give\",\"from\":\"B\",\"item\":\"bread\",\"count\":3}")).orElseThrow();
		assertEquals(Dialogue.Kind.GIVE, a.kind());
		assertFalse(a.first());
		assertEquals("minecraft:bread", a.item());
		assertEquals(3, a.count());
	}

	@Test
	void planForBoth() {
		Dialogue.Agreement a = AnthropicReasoningProvider.parseAgreement(
				json("{\"type\":\"plan\",\"who\":\"both\",\"activity\":\"farm\",\"goal\":\"plant wheat by the river\"}"))
				.orElseThrow();
		assertEquals(Dialogue.Kind.PLAN, a.kind());
		assertTrue(a.both());
		assertEquals("FARM", a.activity());
	}

	@Test
	void nonsenseIsDropped() {
		assertTrue(AnthropicReasoningProvider.parseAgreement(json("{\"type\":\"give\",\"item\":\"\"}")).isEmpty());
		assertTrue(AnthropicReasoningProvider.parseAgreement(json("{\"type\":\"wander\"}")).isEmpty());
	}

	@Test
	void promptListsWhatEachCarries() {
		var a = new DialogueBrief.Speaker("Wren", "Temperament: warm.", "Well fed.", "9 minecraft:bread",
				java.util.List.of("I harvested wheat."), "Sees Nadia as a good friend.");
		var b = new DialogueBrief.Speaker("Nadia", "Temperament: cautious.", "A bit hungry.", "",
				java.util.List.of(), "Sees Wren as a good friend.");
		String prompt = new DialogueBrief(a, b, "morning").toPrompt();
		assertTrue(prompt.contains("Carrying: 9 minecraft:bread."));
		assertTrue(prompt.contains("Carrying: nothing."));
		assertTrue(prompt.contains("\"agreements\""));
	}
}
