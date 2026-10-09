package com.aicivilization.reasoning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SentenceCaseTest {

	@Test
	void lowersAnOpeningVerbButNotNames() {
		assertEquals("find people to share wheat with", ReasoningScheduler.sentenceCase("Find people to share wheat with"));
		assertEquals("Iris and I should build a hut", ReasoningScheduler.sentenceCase("Iris and I should build a hut"));
		assertEquals("AI help", ReasoningScheduler.sentenceCase("AI help"));
		assertEquals("already lower", ReasoningScheduler.sentenceCase("already lower"));
	}
}
