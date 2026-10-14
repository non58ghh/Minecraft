package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.RecipeBook;
import com.aicivilization.perception.Surroundings;
import com.aicivilization.world.RecipeCatalog;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Learning a recipe by watching: an agent who sees a nearby agent's body
 * just produce something it doesn't already know how to make picks up the
 * recipe itself, crediting who it was watched from. Reads only the other
 * agent's body ({@link AgentEntity#recentCraft()}, set the moment a craft
 * or smelt succeeds, visible the way a swung arm or a dropped item would
 * be) — never anything from the watched agent's own mind.
 */
public final class ObserveCrafting {

	/** A craft/smelt is worth noticing for this long afterward (about five seconds). */
	private static final long RECENT_TICKS = 100;

	private ObserveCrafting() {
	}

	/** Checks everyone in sight for something recently made worth picking up. */
	public static void look(AgentMind mind, Surroundings surroundings, long tick, EventLog log) {
		RecipeBook book = mind.recipeBook();
		for (Surroundings.OtherAgentSighting sighting : surroundings.nearbyAgents()) {
			if (!(sighting.entity() instanceof AgentEntity other)) {
				continue;
			}
			Optional<AgentEntity.RecentCraft> recent = other.recentCraft();
			if (recent.isEmpty() || tick - recent.get().tick() > RECENT_TICKS) {
				continue;
			}
			String itemId = recent.get().itemId();
			if (book.knows(itemId)) {
				continue;
			}
			RecipeCatalog.get().plainestRecipeFor(itemId).ifPresent(entry -> {
				RecipeBook.Recipe recipe = entry
						.learnedAs(new RecipeBook.Learned("saw", sighting.displayName(), sighting.agentId(), tick));
				if (book.learn(recipe)) {
					String name = ItemKinds.displayName(itemId);
					mind.perceive(tick, "I watched " + sighting.displayName() + " make " + name + ", and saw how.", 0.35,
							Set.of(sighting.agentId()));
					log.append(tick, EventType.ACTION, List.of(mind.identity().id(), sighting.agentId()),
							mind.identity().name() + " learned how to make " + name + " by watching " + sighting.displayName() + ".",
							List.of());
				}
			});
		}
	}
}
