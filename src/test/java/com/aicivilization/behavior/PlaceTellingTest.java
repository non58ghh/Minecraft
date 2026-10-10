package com.aicivilization.behavior;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceTellingTest {

	@Test
	void directionsAreRoughTheWayAPersonGivesThem() {
		BlockPos here = new BlockPos(100, 64, 100);
		assertEquals("about 60 blocks north-east", ConversationBehavior.whereFrom(here, 142, 58));
		assertEquals("about 40 blocks south", ConversationBehavior.whereFrom(here, 103, 140));
		assertEquals("about 10 blocks west", ConversationBehavior.whereFrom(here, 97, 100));
		assertEquals("about 200 blocks east", ConversationBehavior.whereFrom(here, 298, 110));
	}
}
