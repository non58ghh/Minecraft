package com.aicivilization.mind;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventionTest {

	/** A pressure plate in front of a note block: someone steps on it, the bell rings. */
	static final String DOORBELL = """
			{"name": "Door Bell", "purpose": "To hear when someone is at my door", "expect": "the note block sounds",
			 "parts": [
			   {"x": 0, "y": 0, "z": 0, "block": "stone_pressure_plate"},
			   {"x": 0, "y": 0, "z": 1, "block": "redstone_wire"},
			   {"x": 0, "y": 0, "z": 2, "block": "note_block"}],
			 "trigger": {"x": 0, "y": 0, "z": 0}, "output": {"x": 0, "y": 0, "z": 2}}
			""";

	@Test
	void aSensibleDeviceIsReadWithItsMaterials() {
		Invention bell = Invention.parse(DOORBELL).invention().orElseThrow();
		assertEquals("Door Bell", bell.name());
		assertEquals(Map.of("minecraft:stone_pressure_plate", 1, "minecraft:redstone", 1, "minecraft:note_block", 1), bell.materials());
		Invention again = Invention.parse(bell.toJson().toString()).invention().orElseThrow();
		assertEquals(bell, again, "it survives being saved and read back");
	}

	@Test
	void nonsenseIsTurnedAwayWithAReason() {
		assertTrue(Invention.parse(DOORBELL.replace("\"note_block\"", "\"glowstone\"")).problem().contains("glowstone"),
				"nothing from the Nether");
		assertTrue(Invention.parse(DOORBELL.replace("\"output\": {\"x\": 0, \"y\": 0, \"z\": 2}", "\"output\": {\"x\": 3, \"y\": 0, \"z\": 3}"))
				.problem().contains("output"));
		assertTrue(Invention.parse(DOORBELL.replace("\"x\": 0, \"y\": 0, \"z\": 2, \"block\"", "\"x\": 0, \"y\": 9, \"z\": 2, \"block\""))
				.problem().contains("outside"));
		assertTrue(Invention.parse(DOORBELL.replace("\"stone_pressure_plate\"", "\"cobblestone\"")).problem().contains("trigger"));
		assertTrue(Invention.parse("{not json").invention().isEmpty());
	}

	@Test
	void failedAttemptsAreForgottenBeforeWorkingOnes() {
		AgentMind wren = new AgentMind(new Identity(java.util.UUID.randomUUID(), "Wren", 0), new Personality(0.5, 0.5, 0.5, 0.5),
				new Needs(0.8, 0.8, 0.8, 0.8));
		Invention bell = Invention.parse(DOORBELL).invention().orElseThrow();
		wren.rememberInvention(new KnownInvention(bell, "invented", "Wren", null, 1, true, "the note block sounded"));
		for (int i = 0; i < AgentMind.MAX_INVENTIONS + 3; i++) {
			Invention flop = Invention.parse(DOORBELL.replace("Door Bell", "Attempt " + i)).invention().orElseThrow();
			wren.rememberInvention(new KnownInvention(flop, "invented", "Wren", null, 2 + i, false, "nothing happened"));
		}
		assertEquals(AgentMind.MAX_INVENTIONS, wren.inventions().size());
		assertTrue(wren.inventions().stream().anyMatch(k -> k.works() && k.invention().name().equals("Door Bell")));
	}
}
