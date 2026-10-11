package com.aicivilization.reasoning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpendLedgerTest {

	private static final String REPLY = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
			+ "\"usage\":{\"input_tokens\":1000000,\"output_tokens\":100000}}";

	@Test
	void pricesRepliesByModel() {
		assertEquals(0.15, SpendLedger.cost("claude-haiku-5-5", 1_000_000, 100_000), 1e-9);
		assertEquals(3.00, SpendLedger.cost("claude-sonnet-5-5", 1_000_000, 100_000), 1e-9);
		assertEquals(6.00, SpendLedger.cost("claude-opus-5-5", 1_000_000, 100_000), 1e-9);
		// Something it doesn't know is priced as the dearest, never as free.
		assertEquals(15.00, SpendLedger.cost("some-new-model", 1_000_000, 100_000), 1e-9);
	}

	@Test
	void nearTheCeilingOnlyStoriesThenNothing(@TempDir Path dir) {
		SpendLedger l = new SpendLedger(10, dir.resolve("spend.json"));
		l.record("claude-sonnet-5-5", REPLY); // $3
		l.record("claude-sonnet-5-5", REPLY); // $6
		assertTrue(l.allowsAll());
		l.record("claude-sonnet-5-5", REPLY); // $9: past 90%
		assertFalse(l.allowsAll());
		assertTrue(l.allowsStories());
		l.record("claude-sonnet-5-5", REPLY); // $12
		assertFalse(l.allowsStories());
	}

	@Test
	void aRestartRemembersTheMonthAndANewMonthStartsAfresh(@TempDir Path dir) throws Exception {
		AtomicReference<YearMonth> month = new AtomicReference<>(YearMonth.of(2026, 10));
		Path file = dir.resolve("spend.json");
		new SpendLedger(100, file, month::get).record("claude-sonnet-5-5", REPLY);
		SpendLedger again = new SpendLedger(100, file, month::get);
		assertEquals(3.0, again.spentThisMonth(), 1e-9);
		assertEquals(1, again.callsThisMonth());
		JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals("2026-10", saved.get("month").getAsString());
		month.set(YearMonth.of(2026, 11));
		assertEquals(0.0, again.spentThisMonth(), 1e-9);
		assertTrue(new SpendLedger(100, file, month::get).spentThisMonth() < 1e-9, "last month's file doesn't count");
	}

	@Test
	void theBudgetHoldsToTheLedger(@TempDir Path dir) {
		SpendLedger l = new SpendLedger(10, dir.resolve("spend.json"));
		CallBudget b = new CallBudget(40);
		b.useLedger(l);
		for (int i = 0; i < 3; i++) {
			l.record("claude-sonnet-5-5", REPLY); // $9
		}
		assertFalse(b.hasRoom(false), "minds go on without the LLM");
		assertFalse(b.take(true));
		assertTrue(b.takeStory(), "the chronicle is the last to go");
		l.record("claude-sonnet-5-5", REPLY);
		assertFalse(b.takeStory());
	}

	@Test
	void replyTextSkipsThinking() {
		JsonObject root = JsonParser.parseString("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},"
				+ "{\"type\":\"text\",\"text\":\"{}\"}]}").getAsJsonObject();
		assertEquals("{}", AnthropicReasoningProvider.replyText(root));
		assertThrows(IllegalStateException.class,
				() -> AnthropicReasoningProvider.replyText(JsonParser.parseString("{\"content\":[]}").getAsJsonObject()));
	}

	@Test
	void thinkingIsHandledPerModel() {
		assertTrue(AnthropicReasoningProvider.alwaysThinks("claude-sonnet-5-5"));
		assertTrue(AnthropicReasoningProvider.alwaysThinks("claude-opus-5-5"));
		assertFalse(AnthropicReasoningProvider.alwaysThinks("claude-haiku-5-5"));
		assertTrue(AnthropicReasoningProvider.thinksByDefault("claude-haiku-5-5"));
		assertFalse(AnthropicReasoningProvider.thinksByDefault("claude-sonnet-4-6"));
	}
}
