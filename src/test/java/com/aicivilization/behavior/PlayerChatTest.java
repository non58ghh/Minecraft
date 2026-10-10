package com.aicivilization.behavior;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerChatTest {

	@Test
	void aNamedAgentAnswersElseTheNearest() {
		List<String> near = List.of("Iris", "Bram");
		assertEquals(Optional.of("Bram"), PlayerChat.addressee("hey bram, got any bread?", near, s -> s));
		assertEquals(Optional.of("Iris"), PlayerChat.addressee("hello there", near, s -> s));
		assertEquals(Optional.of("Iris"), PlayerChat.addressee("Bramble is a plant", near, s -> s), "whole names only");
		assertEquals(Optional.empty(), PlayerChat.addressee("anyone?", List.<String>of(), s -> s));
	}
}
