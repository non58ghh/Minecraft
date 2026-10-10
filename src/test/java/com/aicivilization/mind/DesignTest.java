package com.aicivilization.mind;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignTest {

	@Test
	void theHutIsTheOldShelter() {
		Design hut = Design.hut();
		assertTrue(DesignValidator.isValid(hut), DesignValidator.problem(hut).orElse(""));
		assertEquals(23, hut.solids().size());
		assertEquals(new Design.Cell(0, 0, 2), hut.entrance());
		assertEquals(new Design.Cell(0, 0, 0), hut.bed());
		assertTrue(new HashSet<>(hut.solids()).contains(new Design.Cell(0, 2, 1)), "the roof covers the doorway");
	}

	@Test
	void blocksGoUpInOrder() {
		int last = 0;
		for (Design.Cell cell : Design.hut().solids()) {
			assertTrue(cell.dy() >= last);
			last = cell.dy();
		}
	}

	@Test
	void theEntranceIsOutsideTheBuilding() {
		Design design = DesignGenerator.build(5, 7, 3, true, true, 2);
		assertTrue(DesignValidator.isValid(design), DesignValidator.problem(design).orElse(""));
		assertFalse(design.footprintVolume().contains(design.entrance()));
	}

	@Test
	void encodingRoundTrips() {
		Design design = DesignGenerator.build(5, 5, 2, false, true, 1);
		assertEquals(design.layers(), Design.decodeLayers(design.encodedLayers()));
	}

	@Test
	void rejectsBrokenDesigns() {
		// No roof over the inside.
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of("###", "#.#", "#D#"), List.of("###", "#.#", "#D#")))));
		// Inside open to the outside away from the door.
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of("#####", "#....", "##D##"), List.of("#####", "#....", "##D##"), List.of("#####", "#####", "#####")))));
		// Even width.
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of("####", "#..#", "#D##"), List.of("####", "####", "####")))));
		// No door.
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of("###", "#.#", "###"), List.of("###", "###", "###")))));
		// Ragged rows.
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of("###", "#.#", "#D"), List.of("###", "###", "###")))));
		// Wider than the widest allowed.
		String wide = "#".repeat(DesignValidator.MAX_SIDE + 2);
		String inside = "#" + ".".repeat(DesignValidator.MAX_SIDE) + "#";
		String door = wide.substring(0, wide.length() / 2) + "D" + wide.substring(wide.length() / 2 + 1);
		assertFalse(DesignValidator.isValid(new Design("x", "x", List.of(
				List.of(wide, inside, door), List.of(wide, inside, door), List.of(wide, wide, wide)))));
	}

	@Test
	void aSevenBySevenHouseOfFiveLayersIsNowAllowed() {
		String row = "#######";
		Design house = new Design("x", "x", List.of(
				List.of(row, "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "###D###"),
				List.of(row, "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "###D###"),
				List.of(row, "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", row),
				List.of(row, "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", row),
				List.of(row, row, row, row, row, row, row)));
		assertTrue(DesignValidator.isValid(house), DesignValidator.problem(house).orElse(""));
	}

	@Test
	void generatedHomesAreRoomy() {
		Random rng = new Random(11);
		int partitioned = 0;
		for (int i = 0; i < 300; i++) {
			Personality p = new Personality(rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), rng.nextDouble());
			Design design = DesignGenerator.generate("Iris", p, rng.nextLong());
			assertTrue(design.width() >= 5 && design.depth() >= 5, "a home, not a hut: " + design.encodedLayers());
			if (design.layers().get(0).get(1).chars().filter(ch -> ch == '#').count() > 2) {
				partitioned++;
			}
		}
		assertTrue(partitioned > 10, "some homes should have more than one room, got " + partitioned);
	}

	@Test
	void everyGeneratedDesignIsBuildable() {
		Random rng = new Random(7);
		Set<String> shapes = new HashSet<>();
		for (int i = 0; i < 300; i++) {
			Personality p = new Personality(rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), rng.nextDouble());
			Design design = DesignGenerator.generate("Iris", p, rng.nextLong());
			assertTrue(DesignValidator.isValid(design), DesignValidator.problem(design).orElse("") + " " + design);
			assertTrue(design.name().startsWith("Iris's "));
			shapes.add(design.encodedLayers());
			Design copy = DesignGenerator.drift(design, "Marcus", rng);
			assertTrue(DesignValidator.isValid(copy), DesignValidator.problem(copy).orElse("") + " " + copy);
		}
		assertTrue(shapes.size() > 10, "agents should come up with different homes, got " + shapes.size());
	}

	@Test
	void theSameShapeGetsTheSameId() {
		Design a = DesignGenerator.build(5, 5, 2, false, false, 0);
		Design b = DesignGenerator.build(5, 5, 2, false, false, 0);
		assertEquals(DesignGenerator.idFor(a), DesignGenerator.idFor(b));
	}

	@Test
	void aDesignWithoutARoofGetsOne() {
		Design open = new Design("x", "x", List.of(
				List.of("#####", "#...#", "#...#", "#...#", "##D##"),
				List.of("#####", "#...#", "#...#", "#...#", "##D##")));
		assertFalse(DesignValidator.isValid(open));
		Design fixed = DesignValidator.repair(open);
		assertTrue(DesignValidator.isValid(fixed), DesignValidator.problem(fixed).orElse(""));
		assertEquals(3, fixed.height());
		// At full height, the top layer is filled in instead.
		List<List<String>> tallLayers = new java.util.ArrayList<>();
		for (int y = 0; y < DesignValidator.MAX_HEIGHT - 1; y++) {
			tallLayers.add(List.of("###", "#.#", "#D#"));
		}
		tallLayers.add(List.of("###", "#.#", "#.#"));
		Design tall = new Design("t", "t", tallLayers);
		assertTrue(DesignValidator.isValid(DesignValidator.repair(tall)));
	}
}
