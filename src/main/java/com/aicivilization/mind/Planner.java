package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out how to get something: backwards from the thing wanted, through
 * the recipes and sources in the agent's own {@link RecipeBook}, down to
 * what it already carries or can dig up. It knows nothing the agent doesn't:
 * a step it has no recipe or source for is a gap, not a guess.
 *
 * <p>Tools and stations (a pickaxe, a crafting table, a furnace) are needed
 * but not used up; ingredients are used up. Smelting needs fuel: a piece of
 * coal per eight items.
 */
public final class Planner {

	/** How deep a chain of "to make this I first need that" may go. */
	private static final int MAX_DEPTH = 16;
	public static final String CRAFTING_TABLE = "minecraft:crafting_table";
	public static final String FURNACE = "minecraft:furnace";
	public static final String COAL = "minecraft:coal";
	private static final int ITEMS_PER_COAL = 8;

	public enum StepKind {
		/** Dig or pick up {@code count} of {@code item} from {@code block}, holding {@code tool} if named. */
		GATHER,
		/** Make {@code count} of {@code item}, {@code times} goes of the recipe, at its station. */
		CRAFT,
		/** Smelt into {@code count} of {@code item} in a furnace. */
		SMELT
	}

	/** One thing to do. {@code inputs} are what this step uses up, as item id to count. */
	public record Step(StepKind kind, String item, int count, int times, String block, String tool,
			RecipeBook.Station station, Map<String, Integer> inputs) {
		public Step {
			inputs = Map.copyOf(inputs);
		}

		/** "dig 3 iron ore", "make 1 iron pickaxe", "smelt 3 raw iron": how it reads on the timeline. */
		public String describe() {
			String name = item.replaceFirst("^[^:]*:", "").replace('_', ' ');
			return switch (kind) {
				case GATHER -> "get " + count + " " + name;
				case CRAFT -> "make " + count + " " + name;
				case SMELT -> "smelt " + count + " " + name;
			};
		}
	}

	/** The steps in order, or the first thing it doesn't know how to get. */
	public record Result(List<Step> steps, Optional<String> gap) {
		public Result {
			steps = List.copyOf(steps);
		}

		public boolean ok() {
			return gap.isEmpty();
		}
	}

	private Planner() {
	}

	/** Plans to end up holding {@code count} of {@code target}, from what's in {@code carrying}. */
	public static Result plan(RecipeBook book, Map<String, Integer> carrying, String target, int count) {
		Search search = new Search(book, carrying);
		boolean ok = search.need(target, count, 0);
		return new Result(search.steps, ok ? Optional.empty() : Optional.of(search.gap));
	}

	private static final class Search {
		private final RecipeBook book;
		/** What it would be holding at this point in the plan. */
		private final Map<String, Integer> have;
		private final List<Step> steps = new ArrayList<>();
		private final Set<String> working = new HashSet<>();
		private String gap = "";

		Search(RecipeBook book, Map<String, Integer> carrying) {
			this.book = book;
			this.have = new HashMap<>(carrying);
		}

		/** Arranges to hold {@code count} of {@code item} and uses them up. Returns false at a gap. */
		boolean need(String item, int count, int depth) {
			if (!obtain(item, count, depth)) {
				return false;
			}
			have.merge(item, -count, Integer::sum);
			return true;
		}

		/** Arranges to hold one {@code item} that stays held (a tool, a table). */
		boolean hold(String item, int depth) {
			return item == null || item.isEmpty() || obtain(item, 1, depth);
		}

		/** Arranges to be holding at least {@code count} of {@code item}, without using them. */
		boolean obtain(String item, int count, int depth) {
			int missing = count - have.getOrDefault(item, 0);
			if (missing <= 0) {
				return true;
			}
			if (depth > MAX_DEPTH || !working.add(item)) {
				// Too deep, or going round in circles (ingots from a block made of ingots).
				gap = item;
				return false;
			}
			try {
				Optional<RecipeBook.Recipe> recipe = book.recipeFor(item);
				if (recipe.isPresent()) {
					return make(recipe.get(), missing, depth);
				}
				Optional<RecipeBook.Source> source = book.sourceOf(item);
				if (source.isPresent()) {
					if (!hold(source.get().tool(), depth + 1)) {
						return false;
					}
					steps.add(new Step(StepKind.GATHER, item, missing, missing, source.get().block(), source.get().tool(),
							RecipeBook.Station.NONE, Map.of()));
					have.merge(item, missing, Integer::sum);
					return true;
				}
				gap = item;
				return false;
			} finally {
				working.remove(item);
			}
		}

		private boolean make(RecipeBook.Recipe recipe, int missing, int depth) {
			int times = (missing + recipe.count() - 1) / recipe.count();
			Map<String, Integer> used = new HashMap<>();
			for (RecipeBook.Ingredient ingredient : recipe.ingredients()) {
				String option = choose(ingredient, ingredient.count() * times);
				if (!need(option, ingredient.count() * times, depth + 1)) {
					return false;
				}
				used.merge(option, ingredient.count() * times, Integer::sum);
			}
			StepKind kind = recipe.station() == RecipeBook.Station.FURNACE ? StepKind.SMELT : StepKind.CRAFT;
			if (recipe.station() == RecipeBook.Station.TABLE && !hold(CRAFTING_TABLE, depth + 1)) {
				return false;
			}
			if (kind == StepKind.SMELT) {
				int coal = (times + ITEMS_PER_COAL - 1) / ITEMS_PER_COAL;
				if (!hold(FURNACE, depth + 1) || !need(COAL, coal, depth + 1)) {
					return false;
				}
				used.merge(COAL, coal, Integer::sum);
			}
			steps.add(new Step(kind, recipe.result(), times * recipe.count(), times, "", "", recipe.station(), used));
			have.merge(recipe.result(), times * recipe.count(), Integer::sum);
			return true;
		}

		/**
		 * Which of an ingredient's options to use: one already carried in
		 * quantity, else one partly carried, else the first it knows how to get
		 * (any planks: the ones from the logs it has).
		 */
		private String choose(RecipeBook.Ingredient ingredient, int count) {
			String best = null;
			int bestHave = -1;
			for (String option : ingredient.options()) {
				int held = have.getOrDefault(option, 0);
				if (held >= count) {
					return option;
				}
				if (held > bestHave && (held > 0 || book.knows(option))) {
					best = option;
					bestHave = held;
				}
			}
			if (bestHave <= 0) {
				// Nothing carried: prefer an option whose own inputs are carried (oak planks when holding oak logs).
				for (String option : ingredient.options()) {
					Optional<RecipeBook.Recipe> r = book.recipeFor(option);
					if (r.isPresent() && r.get().ingredients().stream()
							.anyMatch(i -> i.options().stream().anyMatch(o -> have.getOrDefault(o, 0) > 0))) {
						return option;
					}
				}
			}
			return best != null ? best : ingredient.options().get(0);
		}
	}
}
