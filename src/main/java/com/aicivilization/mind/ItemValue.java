package com.aicivilization.mind;

/**
 * How much one more (or one fewer) of an item is worth to one agent right
 * now, from its own needs and what it already has. Item meaning arrives as a
 * plain category string decided by the embodiment layer ("food", "building",
 * "seed", "tool", "material", "other"), so this stays engine-agnostic. Pure
 * logic: the basis for trading and for deciding whether to give.
 */
public final class ItemValue {

	private ItemValue() {
	}

	/**
	 * @param food      the agent's food need (1 = full)
	 * @param hasHome   whether it already has a home
	 * @param failedFoodSearches how many searches for food in a row came up empty
	 * @param category  what kind of thing it is
	 * @param nutrition hunger points if food, else 0
	 * @param owned     how many the agent already has
	 * @param needsTool for a tool: whether the agent has none of that kind
	 */
	public static double unitValue(double food, boolean hasHome, int failedFoodSearches, String category, int nutrition,
			int owned, boolean needsTool) {
		double hunger = 1.0 - food;
		return switch (category) {
			// Food is worth most to the hungry, and less the more of it one already has.
			case "food" -> nutrition / 6.0 * (0.4 + 2.0 * hunger) / (1.0 + owned / 8.0);
			// Wood matters while there's a home to build.
			case "building" -> (hasHome ? 0.04 : 0.15) / (1.0 + owned / 32.0);
			case "seed" -> (0.08 + 0.05 * Math.min(failedFoodSearches, 4)) / (1.0 + owned / 16.0);
			case "tool" -> needsTool ? 1.2 : 0.05;
			case "material" -> 0.05 / (1.0 + owned / 16.0);
			default -> 0.02;
		};
	}
}
