package com.aicivilization.mind;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What one agent knows how to make, and where its raw materials come from:
 * recipes (inputs, station, output) and sources (which block gives which
 * item, and with what tool). Each entry says how the agent came to know it,
 * so the planner works only from the agent's own experience and what it has
 * been taught, never from the game's full list. A recipe heard about but not
 * yet made is only a hint: it is kept apart and not planned with.
 */
public final class RecipeBook {

	/** Where a recipe is made. */
	public enum Station {
		/** In the hands (a 2x2 grid's worth). */
		NONE,
		/** At a crafting table. */
		TABLE,
		/** In a furnace. */
		FURNACE
	}

	/** One slot's worth of input: any of these items, this many of them. */
	public record Ingredient(List<String> options, int count) {
		public Ingredient {
			options = List.copyOf(options);
		}

		public boolean accepts(String itemId) {
			return options.contains(itemId);
		}
	}

	/**
	 * How the agent came to know something: "start" (knew it before the
	 * settlement), "made" (did it), "saw" (watched someone), "told" (heard
	 * it; only a hint until made). {@code from} is who, when it came from
	 * someone.
	 */
	public record Learned(String how, String fromName, UUID fromId, long tick) {
		public static Learned atStart() {
			return new Learned("start", "", null, 0);
		}

		public static Learned byDoing(long tick) {
			return new Learned("made", "", null, tick);
		}
	}

	public record Recipe(String result, int count, List<Ingredient> ingredients, Station station, Learned learned) {
		public Recipe {
			ingredients = List.copyOf(ingredients);
		}
	}

	/** A raw material and where it comes from: mining {@code block} with {@code tool} (empty = by hand). */
	public record Source(String item, String block, String tool, Learned learned) {
	}

	/**
	 * A way of working rather than a thing to make. {@link #REPLANTING}: that
	 * a sapling set in the ground grows into a tree. Nobody starts out knowing
	 * one; it's learned by seeing it work, and can be heard of from someone
	 * who has (hearsay, until seen).
	 */
	public static final String REPLANTING = "replanting";
	/**
	 * That marks on a sign can hold words for whoever passes later. Learned
	 * by reading someone else's sign (a player's included) or by trying it
	 * and seeing the words stay; heard of from someone who knows.
	 */
	public static final String WRITING = "writing";

	private final Map<String, Learned> practices = new LinkedHashMap<>();
	private final Map<String, Learned> heardPractices = new LinkedHashMap<>();

	public boolean knowsPractice(String practice) {
		return practices.containsKey(practice);
	}

	/** Heard of it from someone but hasn't seen it work. */
	public boolean heardOfPractice(String practice) {
		return heardPractices.containsKey(practice);
	}

	/** Saw it work (or did it and saw it). Returns whether it was new. */
	public boolean learnPractice(String practice, Learned how) {
		heardPractices.remove(practice);
		return practices.putIfAbsent(practice, how) == null;
	}

	/** Was told of it. Returns whether it was news (not already known or heard). */
	public boolean hearPractice(String practice, Learned how) {
		if (practices.containsKey(practice)) {
			return false;
		}
		return heardPractices.putIfAbsent(practice, how) == null;
	}

	public Map<String, Learned> practices() {
		return Collections.unmodifiableMap(practices);
	}

	public Map<String, Learned> heardPractices() {
		return Collections.unmodifiableMap(heardPractices);
	}

	/** Most hints kept at once; old ones fade. */
	private static final int MAX_HINTS = 32;

	private final Map<String, Recipe> recipes = new LinkedHashMap<>();
	private final Map<String, Source> sources = new LinkedHashMap<>();
	private final Map<String, Recipe> hints = new LinkedHashMap<>();

	/** The recipe it would use to make {@code itemId}, if it knows one. */
	public Optional<Recipe> recipeFor(String itemId) {
		return Optional.ofNullable(recipes.get(itemId));
	}

	public Optional<Source> sourceOf(String itemId) {
		return Optional.ofNullable(sources.get(itemId));
	}

	public boolean knows(String itemId) {
		return recipes.containsKey(itemId) || sources.containsKey(itemId);
	}

	public Collection<Recipe> recipes() {
		return Collections.unmodifiableCollection(recipes.values());
	}

	public Collection<Source> sources() {
		return Collections.unmodifiableCollection(sources.values());
	}

	public Collection<Recipe> hints() {
		return Collections.unmodifiableCollection(hints.values());
	}

	/**
	 * Learns a recipe it has made or knew from the start. Returns whether it
	 * was new. Making something it only had a hint about turns the hint into
	 * knowledge (keeping who it heard it from).
	 */
	public boolean learn(Recipe recipe) {
		if (recipes.containsKey(recipe.result())) {
			return false;
		}
		Recipe hint = hints.remove(recipe.result());
		Recipe kept = hint == null || !"made".equals(recipe.learned().how()) ? recipe
				: new Recipe(recipe.result(), recipe.count(), recipe.ingredients(), recipe.station(),
						new Learned("made", hint.learned().fromName(), hint.learned().fromId(), recipe.learned().tick()));
		recipes.put(recipe.result(), kept);
		return true;
	}

	public boolean learn(Source source) {
		return sources.putIfAbsent(source.item(), source) == null;
	}

	/** Hears how something is made: worth trying, not yet known. Returns whether it was news. */
	public boolean hear(Recipe hint) {
		if (recipes.containsKey(hint.result()) || hints.containsKey(hint.result())) {
			return false;
		}
		if (hints.size() >= MAX_HINTS) {
			hints.remove(hints.keySet().iterator().next());
		}
		hints.put(hint.result(), hint);
		return true;
	}
}
