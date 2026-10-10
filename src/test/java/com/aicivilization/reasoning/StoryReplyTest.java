package com.aicivilization.reasoning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryReplyTest {

	@Test
	void aWellFormedReplyIsRead() {
		StoryText t = AnthropicReasoningProvider.parseStoryJson("""
				Here you go:
				{"headline": "A sheep hunt is set for tomorrow", "text": "Mabry passed the news to Orrin.", "stands": "Hunt planned; no wool yet."}
				""").orElseThrow();
		assertEquals("A sheep hunt is set for tomorrow", t.headline());
		assertEquals("Hunt planned; no wool yet.", t.stands());
	}

	@Test
	void aReplyWithoutTextIsRejected() {
		assertTrue(AnthropicReasoningProvider.parseStoryJson("{\"headline\": \"Only a title\"}").isEmpty());
		assertTrue(AnthropicReasoningProvider.parseStoryJson("not json at all").isEmpty());
	}
}
