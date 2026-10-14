package com.aicivilization.behavior;

import com.aicivilization.action.ItemKinds;
import com.aicivilization.events.Cause;
import com.aicivilization.events.CauseType;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.RecipeBook;
import com.aicivilization.mind.RelationshipData;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Teaching, deliberately, rather than a recipe simply rubbing off: when
 * {@code other} is working toward a goal for something {@code self} knows
 * how to make that {@code other} doesn't, {@code self} may pass it on — or,
 * guarded by the same trust and liking a request for help is, keep it to
 * itself. Either way it's an event, not a silent pass.
 */
final class RecipeTeaching {

	/** Below this willingness (trust and liking blended, as {@link TradeBehavior#requestHelp} scores it), the knower keeps it to itself. */
	private static final double WILLING = 0.3;

	private RecipeTeaching() {
	}

	/** {@code self} teaches {@code other} the recipe for its target goal, or refuses to. Returns whether anything happened. */
	static boolean teach(AgentMind self, AgentMind other, long tick, EventLog log) {
		Optional<Goal> goal = other.targetGoal();
		if (goal.isEmpty()) {
			return false;
		}
		String item = goal.get().targetItem();
		if (other.recipeBook().knows(item) || other.recipeBook().hints().stream().anyMatch(h -> h.result().equals(item))) {
			return false;
		}
		Optional<RecipeBook.Recipe> recipe = self.recipeBook().recipeFor(item);
		if (recipe.isEmpty()) {
			return false;
		}
		String selfName = self.identity().name();
		String otherName = other.identity().name();
		String name = ItemKinds.displayName(item);
		RelationshipData fromSelf = self.relationships().with(other.identity().id());
		double willingness = self.personality().sociability() * 0.3 + (fromSelf.affinity() + 1.0) / 2.0 * 0.4
				+ fromSelf.trust() * 0.3;
		if (willingness < WILLING) {
			self.perceive(tick, "I kept how to make " + name + " to myself; I don't trust " + otherName + " with it.", 0.3,
					Set.of(other.identity().id()));
			other.perceive(tick, "I asked " + selfName + " how to make " + name + ", but they wouldn't say.", 0.35,
					Set.of(self.identity().id()));
			self.relationships().with(other.identity().id()).recordConversation(tick, -0.01, 0.0);
			log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
					selfName + " refused to teach " + otherName + " how to make " + name + ".",
					List.of(new Cause(CauseType.FACTOR, "affinity",
									selfName + "'s liking for " + otherName + " " + String.format(Locale.ROOT, "%.2f", fromSelf.affinity())),
							new Cause(CauseType.FACTOR, "trust",
									selfName + "'s trust in " + otherName + " " + String.format(Locale.ROOT, "%.2f", fromSelf.trust()))),
					List.of(otherName + ": Could you show me how to make " + name + "?",
							selfName + ": I'd rather keep that to myself."));
			self.needs().adjustSocial(0.05);
			other.needs().adjustSocial(0.02);
			return true;
		}
		RecipeBook.Recipe known = recipe.get();
		RecipeBook.Recipe hint = new RecipeBook.Recipe(known.result(), known.count(), known.ingredients(), known.station(),
				new RecipeBook.Learned("told", selfName, self.identity().id(), tick));
		if (!other.recipeBook().hear(hint)) {
			return false;
		}
		other.perceive(tick, selfName + " showed me how to make " + name + ".", 0.5, Set.of(self.identity().id()));
		self.perceive(tick, "I showed " + otherName + " how to make " + name + ".", 0.4, Set.of(other.identity().id()));
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.06, 0.03);
		other.relationships().with(self.identity().id()).recordConversation(tick, 0.04, 0.05);
		log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
				selfName + " taught " + otherName + " how to make " + name + ".", List.of());
		self.needs().adjustSocial(0.1);
		other.needs().adjustSocial(0.08);
		return true;
	}
}
