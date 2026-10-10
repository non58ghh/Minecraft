package com.aicivilization.action;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What goes on a sign, and what a reader takes from one. Plain text both
 * ways, with nothing of Minecraft in it: a sign is four short lines. A
 * reader understands a sign the way a person would, from its words, so a
 * player's sign saying "trees 60 north" means the same as an agent's.
 */
public final class SignWords {

	/** About as many characters as fit on a line of a sign. */
	public static final int LINE_LENGTH = 15;
	/** With a direction but no distance, a sign is taken to mean about this far. */
	static final int DEFAULT_DISTANCE = 32;

	/** The eight ways a sign can point, as unit steps (north is -z, east is +x, as in Minecraft). */
	private static final String[] DIRECTIONS = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
	private static final int[][] STEPS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

	private static final Pattern NUMBER = Pattern.compile("(\\d{1,4})");
	private static final Pattern DIRECTION = Pattern.compile(
			"\\b(north|south)[\\s-]?(east|west)\\b|\\b(north|south|east|west)\\b");
	private static final List<String> TREE_WORDS = List.of("tree", "wood", "forest", "timber", "log");
	private static final List<String> DANGER_WORDS = List.of("beware", "danger", "careful", "zombie", "skeleton", "creeper",
			"spider", "monster");

	private SignWords() {
	}

	/** "north-east" and so on, for a step of {@code dx} east and {@code dz} south. */
	public static String direction(int dx, int dz) {
		double angle = Math.toDegrees(Math.atan2(dz, dx));
		int sector = (int) Math.round(angle / 45.0);
		return DIRECTIONS[Math.floorMod(sector, 8)];
	}

	/** "Trees / 48 blocks / north-east / - Linnea d33": where wood can be had, from here. */
	public static List<String> trees(int dx, int dz, String author, long day) {
		int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz) / 8.0) * 8;
		return List.of("Trees", Math.max(8, distance) + " blocks", direction(dx, dz), signature(author, day));
	}

	/** "Beware / zombies / at night / - Bram d12": somewhere it was attacked. */
	public static List<String> danger(String monster, String author, long day) {
		String what = monster.endsWith("s") ? monster : monster + "s";
		return List.of("Beware", fit(what), "at night", signature(author, day));
	}

	private static String signature(String author, long day) {
		return fit("- " + author + " d" + day);
	}

	private static String fit(String line) {
		return line.length() <= LINE_LENGTH ? line : line.substring(0, LINE_LENGTH);
	}

	/** A sign's lines as one piece of text: "Trees / 48 blocks / north-east". Blank lines are left out. */
	public static String join(List<String> lines) {
		return String.join(" / ", lines.stream().map(String::strip).filter(l -> !l.isEmpty()).toList());
	}

	/**
	 * Where a sign says trees are, as a step {dx, dz} from the sign, if it
	 * says so: a word for trees and a direction, with a distance or without
	 * (then about {@value #DEFAULT_DISTANCE} blocks).
	 */
	public static Optional<int[]> treesAt(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (TREE_WORDS.stream().noneMatch(lower::contains) || lower.contains("no tree") || lower.contains("no wood")) {
			return Optional.empty();
		}
		Matcher dir = DIRECTION.matcher(lower);
		if (!dir.find()) {
			return Optional.empty();
		}
		String direction = dir.group(3) != null ? dir.group(3) : dir.group(1) + "-" + dir.group(2);
		int index = List.of(DIRECTIONS).indexOf(direction);
		Matcher number = NUMBER.matcher(lower);
		int distance = number.find() ? Integer.parseInt(number.group(1)) : DEFAULT_DISTANCE;
		distance = Math.max(4, Math.min(distance, 512));
		double length = Math.hypot(STEPS[index][0], STEPS[index][1]);
		return Optional.of(new int[] {(int) Math.round(STEPS[index][0] / length * distance),
				(int) Math.round(STEPS[index][1] / length * distance)});
	}

	/** Whether a sign warns of danger. */
	public static boolean warns(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		return DANGER_WORDS.stream().anyMatch(lower::contains);
	}
}
