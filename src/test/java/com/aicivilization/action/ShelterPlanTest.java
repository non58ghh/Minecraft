package com.aicivilization.action;

import com.aicivilization.action.ShelterPlan.Cell;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterPlanTest {

	@Test
	void hasFourteenWallBlocksAndNineRoofBlocksWithNoDuplicates() {
		List<Cell> cells = ShelterPlan.cells();

		assertEquals(23, cells.size());
		assertEquals(23, new HashSet<>(cells).size(), "no cell may appear twice");
		assertEquals(14, cells.stream().filter(c -> c.dy() < ShelterPlan.WALL_HEIGHT).count());
		assertEquals(9, cells.stream().filter(c -> c.dy() == ShelterPlan.WALL_HEIGHT).count());
	}

	@Test
	void interiorAndDoorwayStayEmptyBelowTheRoof() {
		Set<Cell> cells = new HashSet<>(ShelterPlan.cells());

		for (int dy = 0; dy < ShelterPlan.WALL_HEIGHT; dy++) {
			assertFalse(cells.contains(new Cell(0, dy, 0)), "interior must stay open at height " + dy);
			assertFalse(cells.contains(new Cell(0, dy, 1)), "doorway must stay open at height " + dy);
		}
		assertTrue(cells.contains(new Cell(0, ShelterPlan.WALL_HEIGHT, 0)), "the roof covers the interior");
		assertTrue(cells.contains(new Cell(0, ShelterPlan.WALL_HEIGHT, 1)), "the roof covers the doorway");
	}

	@Test
	void placesLowerWallsBeforeUpperWallsBeforeTheRoof() {
		int lastHeight = 0;
		for (Cell cell : ShelterPlan.cells()) {
			assertTrue(cell.dy() >= lastHeight, "blocks must go up in order, never back down");
			lastHeight = cell.dy();
		}
	}

	@Test
	void theSpotInFrontOfTheDoorIsOutsideTheFootprint() {
		Cell front = ShelterPlan.FRONT_OF_DOOR;

		assertFalse(ShelterPlan.cells().contains(front));
		assertTrue(Math.abs(front.dx()) <= 1 && front.dz() == 2);
	}

	@Test
	void footprintVolumeIncludesEveryBlockPlusTheOpenInterior() {
		Set<Cell> volume = new HashSet<>(ShelterPlan.footprintVolume());

		assertTrue(volume.containsAll(ShelterPlan.cells()));
		assertTrue(volume.contains(new Cell(0, 0, 0)));
		assertTrue(volume.contains(new Cell(0, 1, 1)));
	}
}
