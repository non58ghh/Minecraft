package com.aicivilization.reasoning;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReplyParseTest {

	@Test
	void readsTheReplyAndWhatsRemembered() {
		Optional<ChatReply> r = AnthropicReasoningProvider.parseChatJson(
				"Sure:\n{\"reply\": \"I've no bread to spare.\", \"remembers\": \"Steve asked me for bread.\"}");
		assertEquals("I've no bread to spare.", r.orElseThrow().reply());
		assertEquals("Steve asked me for bread.", r.orElseThrow().remembers());
		assertTrue(AnthropicReasoningProvider.parseChatJson("no json").isEmpty());
		assertTrue(AnthropicReasoningProvider.parseChatJson("{\"reply\": \"\"}").isEmpty());
	}
}
