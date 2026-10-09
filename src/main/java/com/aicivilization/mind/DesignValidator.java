package com.aicivilization.mind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a {@link Design} is a sensible building to put in the
 * world: small enough, enclosed, roofed, with a way in. Designs from Claude
 * or drift during imitation are checked here; anything that fails is not
 * built (the caller falls back to the hut). Pure logic, unit-tested.
 */
public final class DesignValidator {

	public static final int MAX_SIDE = 7;
	public static final int MAX_HEIGHT = 5;
	public static final int MIN_SOLIDS = 12;
	public static final int MAX_SOLIDS = 110;

	private DesignValidator() {
	}

	/** Empty when the design is fine, else why it isn't. */
	public static Optional<String> problem(Design design) {
		if (design.layers().isEmpty() || design.height() > MAX_HEIGHT) {
			return Optional.of("must have 1 to " + MAX_HEIGHT + " layers");
		}
		int w = design.width();
		int d = design.depth();
		if (w < 3 || d < 3 || w > MAX_SIDE || d > MAX_SIDE || w % 2 == 0 || d % 2 == 0) {
			return Optional.of("width and depth must be odd, 3 to " + MAX_SIDE);
		}
		for (List<String> layer : design.layers()) {
			if (layer.size() != d) {
				return Optional.of("every layer needs " + d + " rows");
			}
			for (String row : layer) {
				if (row.length() != w || !row.matches("[#.D ]+")) {
					return Optional.of("rows must be " + w + " characters of '#', '.', 'D' or space");
				}
			}
		}
		int solids = design.solids().size();
		if (solids < MIN_SOLIDS || solids > MAX_SOLIDS) {
			return Optional.of("needs " + MIN_SOLIDS + " to " + MAX_SOLIDS + " solid blocks, has " + solids);
		}
		Set<Design.Cell> solid = new HashSet<>(design.solids());
		Set<Design.Cell> inside = new HashSet<>(design.interior());
		Set<Design.Cell> door = new HashSet<>(design.doorway());
		if (inside.stream().noneMatch(c -> c.dy() == 0)) {
			return Optional.of("needs a ground-floor inside");
		}
		if (door.stream().noneMatch(c -> c.dy() == 0)) {
			return Optional.of("needs a ground-level doorway");
		}
		for (Design.Cell c : inside) {
			// Roofed: something solid somewhere above every inside cell.
			boolean roofed = false;
			for (int y = c.dy() + 1; y < design.height(); y++) {
				if (solid.contains(new Design.Cell(c.dx(), y, c.dz()))) {
					roofed = true;
					break;
				}
			}
			if (!roofed) {
				return Optional.of("every inside cell needs a roof above it");
			}
			// Enclosed: each side of an inside cell is wall, more inside, or the doorway.
			for (int[] s : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				Design.Cell n = new Design.Cell(c.dx() + s[0], c.dy(), c.dz() + s[1]);
				if (!solid.contains(n) && !inside.contains(n) && !door.contains(n)) {
					return Optional.of("inside is open to the outside away from the doorway");
				}
			}
		}
		// Every inside cell can be reached from the doorway.
		Set<Design.Cell> open = new HashSet<>(inside);
		open.addAll(door);
		Deque<Design.Cell> todo = new ArrayDeque<>();
		Set<Design.Cell> seen = new HashSet<>();
		door.stream().filter(c -> c.dy() == 0).forEach(c -> {
			todo.add(c);
			seen.add(c);
		});
		while (!todo.isEmpty()) {
			Design.Cell c = todo.poll();
			for (int[] s : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
				Design.Cell n = new Design.Cell(c.dx() + s[0], c.dy() + s[1], c.dz() + s[2]);
				if (open.contains(n) && seen.add(n)) {
					todo.add(n);
				}
			}
		}
		if (!seen.containsAll(inside)) {
			return Optional.of("some of the inside can't be reached from the doorway");
		}
		return Optional.empty();
	}

	/**
	 * A near miss made good, if that's all it takes: the commonest slip is
	 * forgetting the roof, so one is put on (a solid layer over the whole
	 * footprint, or the top layer filled in if there's no room for another).
	 * Returns the design unchanged if it's fine or can't be saved this way.
	 */
	public static Design repair(Design design) {
		Optional<String> problem = problem(design);
		if (problem.isEmpty() || !problem.get().contains("roof") || design.layers().isEmpty()) {
			return design;
		}
		java.util.List<java.util.List<String>> layers = new java.util.ArrayList<>(design.layers());
		if (layers.size() < MAX_HEIGHT) {
			java.util.List<String> roof = new java.util.ArrayList<>();
			for (String row : layers.get(0)) {
				StringBuilder r = new StringBuilder();
				for (char c : row.toCharArray()) {
					r.append(c == ' ' ? ' ' : '#');
				}
				roof.add(r.toString());
			}
			layers.add(roof);
		} else {
			java.util.List<String> top = new java.util.ArrayList<>();
			for (String row : layers.get(layers.size() - 1)) {
				top.add(row.replace('.', '#').replace('D', '#'));
			}
			layers.set(layers.size() - 1, top);
		}
		Design fixed = new Design(design.id(), design.name(), layers);
		return isValid(fixed) ? fixed : design;
	}

	public static boolean isValid(Design design) {
		return problem(design).isEmpty();
	}
}
