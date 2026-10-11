package com.aicivilization.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomeChestTest {

	@Test
	void toolsDoorsAndTorchesNeverGoInTheChest() {
		for (String id : new String[] {"minecraft:stone_pickaxe", "minecraft:wooden_axe", "minecraft:iron_sword", Crafting.DOOR,
				Crafting.TORCH, HomeChest.CHEST}) {
			assertEquals(Integer.MAX_VALUE, HomeChest.keep(id), id);
		}
	}
}
