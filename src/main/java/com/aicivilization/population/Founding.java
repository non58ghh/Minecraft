package com.aicivilization.population;

/**
 * Decides how many founders a world gets on server start: only a world
 * that has never had an agent (no minds at all, living or dead), so a
 * settlement that dies out stays dead rather than being quietly refilled.
 */
public final class Founding {

	private Founding() {
	}

	/**
	 * @param configured {@code foundingAgents} from the config; 0 or less means off
	 * @param everLived  minds in the population, living and dead
	 * @param maxAgents  population cap; 0 or less means no cap
	 */
	public static int foundersToSpawn(int configured, int everLived, int maxAgents) {
		if (configured <= 0 || everLived > 0) {
			return 0;
		}
		return maxAgents > 0 ? Math.min(configured, maxAgents) : configured;
	}
}
