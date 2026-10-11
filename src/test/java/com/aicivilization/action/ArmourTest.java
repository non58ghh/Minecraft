package com.aicivilization.action;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmourTest {

	@Test
	void itWearsTheBestPieceItCarriesForEachSlot() {
		AgentMind wren = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		wren.receiveItem(0, "minecraft:leather_helmet", 1);
		wren.receiveItem(0, "minecraft:iron_helmet", 1);
		wren.receiveItem(0, "minecraft:golden_boots", 1);
		assertEquals("minecraft:iron_helmet", Armour.best(wren, "_helmet"));
		assertEquals("minecraft:golden_boots", Armour.best(wren, "_boots"));
		assertEquals(null, Armour.best(wren, "_chestplate"));
	}

	@Test
	void armourIsKnownAndKeptOnTheBody() {
		assertTrue(Armour.isArmour("minecraft:diamond_chestplate"));
		assertTrue(Armour.isArmour("minecraft:chainmail_leggings"));
		assertFalse(Armour.isArmour("minecraft:iron_pickaxe"));
		assertFalse(Armour.isArmour("minecraft:turtle_scute"));
		assertEquals(Integer.MAX_VALUE, HomeChest.keep("minecraft:iron_boots"));
	}
}
