package com.aicivilization.action;

import java.util.ArrayList;
import java.util.List;

/**
 * The blueprint for the small shelter agents build, as offsets from its
 * origin. No Minecraft types, so it is unit-testable.
 *
 * <p>The origin is the floor cell at the centre of a 3x3 footprint. The
 * interior (the origin cell and the one above it) stays empty. The eight
 * cells around it become walls two blocks high, except the doorway on the
 * {@code +z} side, and a flat roof covers the whole footprint one block
 * above the walls: 14 wall blocks and 9 roof blocks, 23 in all.
 */
public final class ShelterPlan {

	public record Cell(int dx, int dy, int dz) {
	}

	/** Offset from the origin to the cell just outside the doorway. */
	public static final Cell FRONT_OF_DOOR = new Cell(0, 0, 2);

	public static final int WALL_HEIGHT = 2;

	private static final List<Cell> CELLS = buildCells();
	private static final List<Cell> FOOTPRINT_VOLUME = buildFootprintVolume();

	private ShelterPlan() {
	}

	/** Every block of the shelter, in the order it should be placed: lower walls, upper walls, roof. */
	public static List<Cell> cells() {
		return CELLS;
	}

	/** Cells that must be empty for the shelter to fit: its own cells plus the interior. */
	public static List<Cell> footprintVolume() {
		return FOOTPRINT_VOLUME;
	}

	private static List<Cell> buildCells() {
		List<Cell> cells = new ArrayList<>();
		for (int dy = 0; dy < WALL_HEIGHT; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					boolean interior = dx == 0 && dz == 0;
					boolean doorway = dx == 0 && dz == 1;
					if (!interior && !doorway) {
						cells.add(new Cell(dx, dy, dz));
					}
				}
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				cells.add(new Cell(dx, WALL_HEIGHT, dz));
			}
		}
		return List.copyOf(cells);
	}

	private static List<Cell> buildFootprintVolume() {
		List<Cell> volume = new ArrayList<>(CELLS);
		for (int dy = 0; dy < WALL_HEIGHT; dy++) {
			volume.add(new Cell(0, dy, 0));
			volume.add(new Cell(0, dy, 1));
		}
		return List.copyOf(volume);
	}
}
