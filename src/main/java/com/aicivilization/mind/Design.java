package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A building an agent knows how to make, as cells relative to its origin
 * (the floor cell at the middle of the inside). Pure data with no Minecraft
 * types: which block goes in each solid cell is decided when building, from
 * whatever logs, planks or stone the builder carries.
 *
 * <p>Designs are drawn as text layers, bottom first: {@code #} solid,
 * {@code .} inside (kept empty), {@code D} doorway (kept empty), space =
 * nothing. Every layer has the same odd width and depth, centred on the
 * origin. This is also the format Claude is asked to answer in.
 *
 * @param id     stable id, e.g. "hut" or "d-3f2a9c1e"
 * @param name   what its maker calls it, e.g. "Iris's lookout"
 * @param layers the drawing, bottom layer first, each layer's rows north to south
 */
public record Design(String id, String name, List<List<String>> layers) {

	public record Cell(int dx, int dy, int dz) {
	}

	public Design {
		layers = layers.stream().map(List::copyOf).toList();
	}

	/** The small hut every agent knows from the start: 3x3, walls 2 high, a doorway, a flat roof. */
	public static Design hut() {
		return new Design("hut", "hut", List.of(
				List.of("###", "#.#", "#D#"),
				List.of("###", "#.#", "#D#"),
				List.of("###", "###", "###")));
	}

	/** What kind of building it is, without whose idea it was: "homestead" for "Idris's homestead". */
	public String kind() {
		int i = name.indexOf("'s ");
		return i >= 0 ? name.substring(i + 3) : name;
	}

	/** The drawing as one line: rows joined by '/', layers by '|'. For saving and sharing. */
	public String encodedLayers() {
		return String.join("|", layers.stream().map(rows -> String.join("/", rows)).toList());
	}

	public static List<List<String>> decodeLayers(String encoded) {
		List<List<String>> out = new ArrayList<>();
		for (String layer : encoded.split("\\|", -1)) {
			out.add(List.of(layer.split("/", -1)));
		}
		return out;
	}

	public int width() {
		return layers.isEmpty() || layers.get(0).isEmpty() ? 0 : layers.get(0).get(0).length();
	}

	public int depth() {
		return layers.isEmpty() ? 0 : layers.get(0).size();
	}

	public int height() {
		return layers.size();
	}

	private List<Cell> cellsOf(char kind) {
		List<Cell> out = new ArrayList<>();
		int halfW = width() / 2;
		int halfD = depth() / 2;
		for (int dy = 0; dy < layers.size(); dy++) {
			List<String> rows = layers.get(dy);
			for (int r = 0; r < rows.size(); r++) {
				String row = rows.get(r);
				for (int c = 0; c < row.length(); c++) {
					if (row.charAt(c) == kind) {
						out.add(new Cell(c - halfW, dy, r - halfD));
					}
				}
			}
		}
		return out;
	}

	/** Every solid block, in building order: bottom layer first. */
	public List<Cell> solids() {
		return cellsOf('#');
	}

	public List<Cell> interior() {
		return cellsOf('.');
	}

	public List<Cell> doorway() {
		return cellsOf('D');
	}

	/** Cells that must be free for the building to fit: solids, inside and doorway. */
	public List<Cell> footprintVolume() {
		List<Cell> all = new ArrayList<>(solids());
		all.addAll(interior());
		all.addAll(doorway());
		return all;
	}

	/** Ground-level cells under the building, which need firm ground beneath them. */
	public List<Cell> groundCells() {
		List<Cell> out = new ArrayList<>();
		for (Cell cell : footprintVolume()) {
			if (cell.dy() == 0) {
				out.add(cell);
			}
		}
		return out;
	}

	/** Where to stand while building or arriving: just outside the lowest doorway cell. */
	public Cell entrance() {
		Set<Cell> taken = new HashSet<>(footprintVolume());
		for (Cell door : doorway()) {
			if (door.dy() != 0) {
				continue;
			}
			for (int[] step : new int[][] {{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
				Cell out = new Cell(door.dx() + step[0], 0, door.dz() + step[1]);
				if (!taken.contains(out)) {
					return out;
				}
			}
		}
		return new Cell(0, 0, depth() / 2 + 1);
	}

	/** A spot inside to stand or sleep in. */
	public Cell bed() {
		List<Cell> inside = interior();
		for (Cell cell : inside) {
			if (cell.dy() == 0) {
				return cell;
			}
		}
		return new Cell(0, 0, 0);
	}
}
