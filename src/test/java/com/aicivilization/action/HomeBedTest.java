package com.aicivilization.action;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeBedTest {

	private static AgentMind mind() {
		return new AgentMind(new Identity(UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
	}

	@Test
	void aBedNeedsThreeWoolOfOneColour() {
		AgentMind wren = mind();
		wren.receiveItem(0, "minecraft:white_wool", 2);
		wren.receiveItem(0, "minecraft:red_wool", 1);
		assertNull(Crafting.bedWool(wren), "wool of two colours doesn't make a bed");
		wren.receiveItem(2, "minecraft:white_wool", 1);
		assertEquals("white", Crafting.bedWool(wren));
		wren.receiveItem(3, "minecraft:light_blue_wool", 5);
		assertEquals("white", Crafting.bedWool(wren), "the first colour it has enough of");
	}

	@Test
	void bedsAreBeds() {
		assertTrue(HomeBed.isBed("minecraft:light_blue_bed"));
		assertFalse(HomeBed.isBed("minecraft:bedrock"));
	}

	@Test
	void aBedIsNeverPutInTheChest() {
		assertEquals(Integer.MAX_VALUE, HomeChest.keep("minecraft:white_bed"));
	}

	@Test
	void anIronDoorNeedsTwoButtonsAndStoneComesFirst() {
		AgentMind wren = mind();
		wren.receiveItem(0, "minecraft:oak_button", 1);
		assertNull(IronDoor.button(wren), "one button isn't enough for both sides");
		wren.receiveItem(0, "minecraft:oak_button", 1);
		assertEquals("minecraft:oak_button", IronDoor.button(wren));
		wren.receiveItem(0, "minecraft:stone_button", 2);
		assertEquals("minecraft:stone_button", IronDoor.button(wren));
	}
}
