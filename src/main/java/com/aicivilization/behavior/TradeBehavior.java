package com.aicivilization.behavior;

import com.aicivilization.action.Crafting;
import com.aicivilization.action.ItemKinds;
import com.aicivilization.events.Cause;
import com.aicivilization.events.CauseType;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.ItemValue;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.RelationshipData;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Trading and helping, as two agents talking face to face. Each side values
 * things by its own situation ({@link ItemValue}); a trade only happens if
 * both come out ahead by their own lights (a trusted partner gets a little
 * slack), and help is given when the one asked can spare it and is inclined
 * to (sociable, fond of or trusting the asker). Both remember how it went,
 * and it changes how they feel about each other.
 */
final class TradeBehavior {

	private static final int MAX_LOT = 16;
	/** Meals put by before an agent offers food unasked. */
	private static final int SPARE_MEALS = 8;
	/** Not worth haggling over less than this. */
	private static final double MIN_GAIN = 0.08;

	private TradeBehavior() {
	}

	/** Self proposes the best swap it can think of. Returns whether a trade happened. */
	static boolean offerTrade(AgentMind self, AgentMind other, long tick, EventLog log) {
		Deal best = null;
		for (Possession give : self.possessions()) {
			if (give.quantity() <= 0 || keepsBack(self, give.itemId())) {
				continue;
			}
			for (Possession get : other.possessions()) {
				if (get.quantity() <= 0 || get.itemId().equals(give.itemId()) || keepsBack(other, get.itemId())
						|| ItemKinds.nutrition(get.itemId()) > 0 && ItemKinds.nutrition(give.itemId()) > 0) {
					// Swapping one food for another feeds nobody.
					continue;
				}
				double otherGets = value(other, give.itemId());
				double otherLoses = value(other, get.itemId());
				if (otherGets <= 0) {
					continue;
				}
				// One of what self wants, paid for with enough of its own goods that the other sees it as fair.
				int getQty = 1;
				int giveQty = (int) Math.ceil(otherLoses * getQty / otherGets);
				if (giveQty < 1 || giveQty > Math.min(give.quantity(), MAX_LOT)) {
					continue;
				}
				double selfSurplus = getQty * value(self, get.itemId()) - giveQty * value(self, give.itemId());
				double slack = other.relationships().get(self.identity().id()).map(RelationshipData::trust).orElse(0.0) * 0.1;
				double otherSurplus = giveQty * otherGets - getQty * otherLoses + slack;
				if (selfSurplus > MIN_GAIN && otherSurplus >= 0 && (best == null || selfSurplus > best.selfSurplus)) {
					best = new Deal(give.itemId(), giveQty, get.itemId(), getQty, selfSurplus);
				}
			}
		}
		String selfName = self.identity().name();
		String otherName = other.identity().name();
		if (best == null) {
			self.perceive(tick, "I tried to trade with " + otherName + ", but we had nothing the other wanted.", 0.05,
					Set.of(other.identity().id()));
			return false;
		}
		if (!self.takeItem(best.give, best.giveQty) || !other.takeItem(best.get, best.getQty)) {
			return false;
		}
		other.receiveItem(tick, best.give, best.giveQty);
		self.receiveItem(tick, best.get, best.getQty);
		String gave = best.giveQty + " " + ItemKinds.displayName(best.give);
		String got = best.getQty + " " + ItemKinds.displayName(best.get);
		self.perceive(tick, "I traded " + gave + " to " + otherName + " for " + got + ".", 0.5, Set.of(other.identity().id()));
		other.perceive(tick, selfName + " traded me " + gave + " for " + got + ".", 0.5, Set.of(self.identity().id()));
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.05, 0.06);
		other.relationships().with(self.identity().id()).recordConversation(tick, 0.05, 0.06);
		log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
				selfName + " traded " + gave + " to " + otherName + " for " + got + ".", List.of());
		return true;
	}

	/** Self, in need, asks for food (or a tool it lacks). Returns whether the other helped. */
	static boolean requestHelp(AgentMind self, AgentMind other, long tick, EventLog log) {
		String selfName = self.identity().name();
		String otherName = other.identity().name();
		Optional<String> wanted = Optional.empty();
		boolean hungry = self.needs().food() < 0.4;
		for (Possession p : other.possessions()) {
			boolean food = ItemKinds.nutrition(p.itemId()) > 0;
			// The other can spare it if it isn't hungry itself or has plenty.
			boolean spare = food ? other.needs().food() > 0.5 || p.quantity() > 3 : p.quantity() > 1;
			if (spare && (hungry && food || !hungry && isTool(p.itemId()) && self.countOf(p.itemId()) == 0)) {
				wanted = Optional.of(p.itemId());
				break;
			}
		}
		RelationshipData fromOther = other.relationships().with(self.identity().id());
		double generosity = other.personality().sociability() * 0.4 + (fromOther.affinity() + 1.0) / 2.0 * 0.3
				+ fromOther.trust() * 0.3;
		if (wanted.isEmpty() || generosity < 0.35) {
			Refusal why = wanted.isEmpty() ? nothingToSpare(other, hungry) : unwilling(other, fromOther, selfName);
			String what = hungry ? "food" : "a tool";
			self.perceive(tick, "I asked " + otherName + " for " + what + ", but " + why.toAsker(otherName, "me") + ".", 0.4,
					Set.of(other.identity().id()));
			other.perceive(tick, selfName + " asked me for " + what + "; I turned them down because " + why.toSelf() + ".",
					0.3, Set.of(self.identity().id()));
			// Not being able to spare anything is understandable; being refused out of distrust stings.
			self.relationships().with(other.identity().id()).recordConversation(tick, why.unwilling() ? -0.04 : 0.0, 0.0);
			log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
					selfName + " asked " + otherName + " for " + what + " and was turned away: " + why.toAsker(otherName, selfName) + ".",
					List.of(Cause.needState("food", self.needs().food()),
							new Cause(CauseType.FACTOR, "reason", why.toAsker(otherName, selfName)),
							new Cause(CauseType.FACTOR, "trust", otherName + "'s trust in " + selfName + " "
									+ String.format(Locale.ROOT, "%.2f", fromOther.trust())),
							new Cause(CauseType.FACTOR, "affinity", otherName + "'s liking for " + selfName + " "
									+ String.format(Locale.ROOT, "%.2f", fromOther.affinity()))),
					List.of(selfName + ": " + (hungry ? "Could you spare me something to eat? I'm really hungry."
									: "Have you got a tool you could spare?"),
							otherName + ": " + why.spoken()));
			return false;
		}
		int qty = 1;
		if (!other.takeItem(wanted.get(), qty)) {
			return false;
		}
		self.receiveItem(tick, wanted.get(), qty);
		String what = ItemKinds.displayName(wanted.get());
		self.perceive(tick, otherName + " gave me some " + what + " when I needed it.", 0.6, Set.of(other.identity().id()));
		other.perceive(tick, "I gave " + selfName + " some " + what + "; they needed it.", 0.4, Set.of(self.identity().id()));
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.12, 0.1);
		fromOther.recordConversation(tick, 0.04, 0.02);
		other.needs().adjustBelonging(0.05);
		log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
				otherName + " gave " + selfName + " some " + what + ".", List.of());
		return true;
	}

	/** Meals' worth of food carried (a meal being about a loaf of bread). */
	static int foodMeals(AgentMind mind) {
		int points = 0;
		for (Possession p : mind.possessions()) {
			points += ItemKinds.nutrition(p.itemId()) * p.quantity();
		}
		return points / 5;
	}

	private static int buildingStock(AgentMind mind) {
		int blocks = 0;
		for (Possession p : mind.possessions()) {
			if (ItemKinds.isBuildingMaterial(p.itemId())) {
				blocks += p.quantity();
			}
		}
		return blocks;
	}

	/** Whether self has food to spare: well fed, with a few meals put by. */
	static boolean hasFoodToSpare(AgentMind mind) {
		return mind.needs().food() > 0.5 && foodMeals(mind) >= SPARE_MEALS;
	}

	/**
	 * Self, well stocked, offers food to a hungry other unasked: a meal or
	 * two of what it can best spare. Returns whether it gave anything.
	 */
	static boolean offerFood(AgentMind self, AgentMind other, long tick, EventLog log) {
		String best = null;
		int bestNutrition = 0;
		for (Possession p : self.possessions()) {
			int n = ItemKinds.nutrition(p.itemId());
			if (n > bestNutrition && p.quantity() > 0) {
				best = p.itemId();
				bestNutrition = n;
			}
		}
		if (best == null) {
			return false;
		}
		int qty = Math.min(self.countOf(best), Math.max(1, 10 / bestNutrition));
		if (!self.takeItem(best, qty)) {
			return false;
		}
		other.receiveItem(tick, best, qty);
		String what = qty + " " + ItemKinds.displayName(best);
		String selfName = self.identity().name();
		String otherName = other.identity().name();
		other.perceive(tick, selfName + " saw I was hungry and gave me " + what + ".", 0.6, Set.of(self.identity().id()));
		self.perceive(tick, otherName + " was hungry, so I gave them " + what + ".", 0.4, Set.of(other.identity().id()));
		other.relationships().with(self.identity().id()).recordConversation(tick, 0.15, 0.12);
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.05, 0.02);
		self.needs().adjustBelonging(0.05);
		log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
				selfName + " saw " + otherName + " was hungry and gave them " + what + ".", List.of());
		return true;
	}

	/** Whether this agent could do with some help, or has something to trade. */
	static boolean inNeed(AgentMind mind) {
		return mind.needs().food() < 0.4;
	}

	private static double value(AgentMind mind, String itemId) {
		int nutrition = ItemKinds.nutrition(itemId);
		// Plenty is judged across the whole kind: a pack full of bread makes a porkchop no more welcome.
		int owned = nutrition > 0 ? foodMeals(mind) * 5 / Math.max(1, nutrition)
				: ItemKinds.isBuildingMaterial(itemId) ? buildingStock(mind)
				: mind.countOf(itemId);
		String category = nutrition > 0 ? "food"
				: ItemKinds.isBuildingMaterial(itemId) ? "building"
				: itemId.endsWith("_seeds") ? "seed"
				: isTool(itemId) ? "tool"
				: itemId.endsWith("stick") || itemId.endsWith("cobblestone") ? "material"
				: "other";
		boolean needsTool = false;
		if (category.equals("tool")) {
			for (Crafting.Tool tool : Crafting.Tool.values()) {
				if (itemId.endsWith("_" + tool.name().toLowerCase())) {
					needsTool = Crafting.best(mind, tool).isEmpty();
				}
			}
		}
		return ItemValue.unitValue(mind.needs().food(), mind.home().isPresent(), mind.failedFoodSearches(), category,
				nutrition, owned, needsTool);
	}

	private static boolean isTool(String itemId) {
		return itemId.startsWith("minecraft:wooden_") || itemId.startsWith("minecraft:stone_")
				&& !itemId.equals("minecraft:stone_bricks");
	}

	/** An agent won't trade away its last tool of a kind, or its last bit of food while hungry. */
	static boolean keepsBack(AgentMind mind, String itemId) {
		if (isTool(itemId)) {
			return mind.countOf(itemId) <= 1;
		}
		return ItemKinds.nutrition(itemId) > 0 && mind.needs().food() < 0.3 && mind.countOf(itemId) <= 1;
	}

	/**
	 * Why a request for help was refused, as the asker hears it, as the one
	 * refusing thinks of it, and as said out loud. {@code unwilling} is true
	 * when the other could have helped but chose not to.
	 */
	private record Refusal(String toAskerTemplate, String toSelf, String spoken, boolean unwilling) {
		/** {@code asker} is the asker's name, or "me" in the asker's own memory. */
		String toAsker(String otherName, String asker) {
			return toAskerTemplate.replace("{other}", otherName).replace("{asker}", asker);
		}
	}

	private static Refusal nothingToSpare(AgentMind other, boolean hungry) {
		if (!hungry) {
			return new Refusal("{other} had no tool to spare", "I had no tool to spare",
					"I've only got the one of each, and I need them.", false);
		}
		if (foodMeals(other) == 0) {
			return new Refusal("{other} had no food at all", "I had no food at all",
					"I've got nothing. I haven't eaten well myself.", false);
		}
		return new Refusal("{other} was hungry too and had barely enough for themselves",
				"I was hungry myself and had barely enough", "I'm sorry, I've barely enough to get by myself.", false);
	}

	private static Refusal unwilling(AgentMind other, RelationshipData fromOther, String askerName) {
		if (fromOther.affinity() < -0.1) {
			return new Refusal("{other} doesn't like {asker}", "I don't like " + askerName,
					"Why would I help you? Find your own.", true);
		}
		if (fromOther.trust() < 0.2) {
			return new Refusal("{other} doesn't know or trust {asker} well enough yet", "I don't know " + askerName
					+ " well enough to trust them", "I don't really know you. I need to look after my own.", true);
		}
		return new Refusal("{other} wasn't feeling generous", "I didn't feel like giving any away",
				"Not today. I need what I've got.", true);
	}

	private record Deal(String give, int giveQty, String get, int getQty, double selfSurplus) {
	}
}
