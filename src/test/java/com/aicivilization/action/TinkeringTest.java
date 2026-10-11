package com.aicivilization.action;

import com.aicivilization.events.EventLog;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Invention;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinkeringTest {

	@Test
	void atTheDoorZRunsAwayFromItAndXToTheRight() {
		// A door facing east: out is east, right (looking out) is south.
		Tinkering.Site site = new Tinkering.Site(new BlockPos(10, 64, 10), Direction.EAST);
		assertEquals(new BlockPos(12, 64, 10), site.at(new Invention.Cell(0, 0, 2)));
		assertEquals(new BlockPos(10, 65, 11), site.at(new Invention.Cell(1, 1, 0)));
		assertEquals("east", site.facing("away"));
		assertEquals("west", site.facing("toward_door"));
		assertEquals("south", site.facing("right"));
		assertEquals("north", site.facing("left"));
		assertEquals("up", site.facing("up"));
	}

	@Test
	void anIdeaBecomesAPlanAndNoNeedIsNoPlan() {
		AgentMind wren = new AgentMind(new Identity(UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		EventLog log = new EventLog();
		Inventing.consider(wren, "{\"invent\": false}", 10, log);
		assertNull(wren.plannedInvention());
		String bell = com.aicivilization.mind.InventionTest.DOORBELL.replaceFirst("\\{", "{\"invent\": true, \"problem\": \"I never know who's outside\", ");
		Inventing.consider(wren, bell, 20, log);
		assertEquals("Door Bell", wren.plannedInvention().name());
		assertTrue(log.all().stream().anyMatch(e -> e.summary().contains("came up with an idea for a device: Door Bell")));
		Inventing.consider(wren, "{\"invent\": true, \"name\": \"Mess\", \"purpose\": \"x\", \"parts\": []}", 30, log);
		assertEquals("Door Bell", wren.plannedInvention().name(), "a muddle doesn't replace a good idea");
	}
}
