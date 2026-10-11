package com.aicivilization.mind;

/** Experience levels as the game counts them: each level costs more points than the last. */
public final class Experience {

	private Experience() {
	}

	/** Points needed to reach {@code level} from nothing. */
	public static int pointsFor(int level) {
		if (level <= 16) {
			return level * level + 6 * level;
		}
		if (level <= 31) {
			return (int) (2.5 * level * level - 40.5 * level + 360);
		}
		return (int) (4.5 * level * level - 162.5 * level + 2220);
	}

	/** The level these points make. */
	public static int levelFor(int points) {
		int level = 0;
		while (pointsFor(level + 1) <= points) {
			level++;
		}
		return level;
	}
}
