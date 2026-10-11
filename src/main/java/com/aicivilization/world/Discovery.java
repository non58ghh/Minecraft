package com.aicivilization.world;

import com.aicivilization.mind.RecipeBook;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Working out how to make something new. Nobody is taught every recipe: an
 * agent who wants a thing it doesn't know how to make can work it out, the
 * way a player tries things at a table, but only from what it already knows
 * how to get or has in hand. Iron armour needs iron ingots it can already
 * make; an enchanting table needs it to know where diamonds and obsidian
 * come from first. Curious agents work things out sooner.
 */
public final class Discovery {

	private Discovery() {
	}

	/**
	 * Of the game's ways to make {@code item}, the plainest one whose every
	 * ingredient it either carries or knows how to get. Empty if none is.
	 */
	public static Optional<RecipeCatalog.Entry> workable(RecipeBook book, Set<String> carried, String item, RecipeCatalog catalog) {
		return catalog.recipesFor(item).stream()
				.filter(e -> e.ingredients().stream()
						.allMatch(i -> i.options().stream().anyMatch(o -> carried.contains(o) || book.knows(o))))
				.min(Comparator.comparingInt((RecipeCatalog.Entry e) -> e.ingredients().stream()
						.mapToInt(RecipeBook.Ingredient::count).sum()));
	}

	/** The chance, each time it turns the problem over, that it works it out. */
	public static double chance(double curiosity) {
		return 0.08 + 0.2 * curiosity;
	}
}
