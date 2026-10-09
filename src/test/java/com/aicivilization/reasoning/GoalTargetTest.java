package com.aicivilization.reasoning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalTargetTest {

	@Test
	void targetNamesBecomeItemIds() {
		assertEquals("minecraft:iron_pickaxe", ReasoningScheduler.itemId("iron_pickaxe"));
		assertEquals("minecraft:iron_pickaxe", ReasoningScheduler.itemId(" Iron Pickaxe "));
		assertEquals("minecraft:iron_pickaxe", ReasoningScheduler.itemId("minecraft:iron_pickaxe"));
	}
}
