package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Makes building designs without an LLM: an agent's own design when Claude
 * isn't configured (or its answer didn't hold up), and the small changes a
 * design picks up when someone copies it. Every design returned passes
 * {@link DesignValidator}. Pure logic, unit-tested.
 */
public final class DesignGenerator {

	private static final String[] SMALL = {"cabin", "hut", "den", "lodge", "cottage", "hideout", "shack"};
	private static final String[] LARGE = {"hall", "longhouse", "homestead", "lodge", "house", "great hut"};
	private static final String[] TALL = {"tower", "lookout", "keep"};

	private DesignGenerator() {
	}

	/**
	 * One agent's own idea of a home, shaped by its personality: ambitious
	 * agents think bigger, curious ones taller or with a raised roof, and
	 * where the door goes is a matter of taste.
	 */
	public static Design generate(String agentName, Personality personality, long seed) {
		Random random = new Random(seed);
		int width = personality.ambition() > 0.55 || random.nextDouble() < 0.3 ? 5 : 3;
		int depth = personality.ambition() > 0.75 ? 7 : random.nextDouble() < 0.5 ? 5 : 3;
		if (width == 3 && depth == 3) {
			depth = 5;
		}
		int walls = personality.curiosity() > 0.6 && random.nextBoolean() ? 3 : 2;
		boolean raisedRoof = personality.curiosity() > 0.5 && random.nextBoolean() && width >= 5 && depth >= 5;
		boolean openCorners = random.nextDouble() < 0.4;
		int door = random.nextInt(4);
		String kind = walls == 3 && width == 3 ? pick(TALL, random)
				: width * depth >= 25 ? pick(LARGE, random) : pick(SMALL, random);
		Design design = build(width, depth, walls, raisedRoof, openCorners, door);
		Design named = new Design(idFor(design), agentName + "'s " + kind, design.layers());
		return DesignValidator.isValid(named) ? named : Design.hut();
	}

	/**
	 * A copy of {@code original} as someone else remembers it: usually the
	 * same, sometimes a little wider, deeper, taller or with the door moved.
	 * Falls back to the original if the change wouldn't hold up.
	 */
	public static Design drift(Design original, String copierName, Random random) {
		if (random.nextDouble() < 0.6) {
			return original;
		}
		int width = original.width();
		int depth = original.depth();
		int walls = Math.max(2, original.height() - 1);
		boolean raisedRoof = false;
		boolean openCorners = original.layers().get(0).get(0).charAt(0) == ' ';
		int door = 0;
		switch (random.nextInt(4)) {
			case 0 -> width = Math.min(DesignValidator.MAX_SIDE, width + 2);
			case 1 -> depth = Math.min(DesignValidator.MAX_SIDE, depth + 2);
			case 2 -> walls = walls == 2 ? 3 : 2;
			default -> door = 1 + random.nextInt(3);
		}
		Design changed = build(width, depth, Math.min(walls, DesignValidator.MAX_HEIGHT - 1), raisedRoof, openCorners, door);
		String base = original.name().contains("'s ") ? original.name().substring(original.name().indexOf("'s ") + 3) : original.name();
		Design named = new Design(idFor(changed), copierName + "'s " + base, changed.layers());
		return DesignValidator.isValid(named) ? named : original;
	}

	/** A stable id from the shape alone, so the same building is recognised wherever it's seen. */
	public static String idFor(Design design) {
		return "d-" + Integer.toHexString(design.encodedLayers().hashCode());
	}

	/**
	 * A rectangular building: {@code walls} layers of wall around an open
	 * inside, then a solid roof, optionally a smaller second roof layer on
	 * top. {@code door} picks the side: 0 south, 1 north, 2 east, 3 west.
	 */
	static Design build(int width, int depth, int walls, boolean raisedRoof, boolean openCorners, int door) {
		List<List<String>> layers = new ArrayList<>();
		for (int y = 0; y < walls; y++) {
			List<String> rows = new ArrayList<>();
			for (int r = 0; r < depth; r++) {
				StringBuilder row = new StringBuilder();
				for (int c = 0; c < width; c++) {
					boolean edgeR = r == 0 || r == depth - 1;
					boolean edgeC = c == 0 || c == width - 1;
					char ch;
					if (edgeR && edgeC) {
						ch = openCorners ? ' ' : '#';
					} else if (edgeR || edgeC) {
						ch = isDoor(r, c, width, depth, door) && y < 2 ? 'D' : '#';
					} else {
						ch = '.';
					}
					row.append(ch);
				}
				rows.add(row.toString());
			}
			layers.add(rows);
		}
		List<String> roof = new ArrayList<>();
		for (int r = 0; r < depth; r++) {
			roof.add(openCorners && (r == 0 || r == depth - 1)
					? " " + "#".repeat(width - 2) + " "
					: "#".repeat(width));
		}
		layers.add(roof);
		if (raisedRoof && walls + 2 <= DesignValidator.MAX_HEIGHT) {
			List<String> top = new ArrayList<>();
			for (int r = 0; r < depth; r++) {
				boolean edge = r == 0 || r == depth - 1;
				top.add(edge ? " ".repeat(width) : " " + "#".repeat(width - 2) + " ");
			}
			layers.add(top);
		}
		return new Design("tmp", "tmp", layers);
	}

	private static boolean isDoor(int r, int c, int width, int depth, int door) {
		return switch (door) {
			case 1 -> r == 0 && c == width / 2;
			case 2 -> c == width - 1 && r == depth / 2;
			case 3 -> c == 0 && r == depth / 2;
			default -> r == depth - 1 && c == width / 2;
		};
	}

	private static String pick(String[] options, Random random) {
		return options[random.nextInt(options.length)];
	}
}
