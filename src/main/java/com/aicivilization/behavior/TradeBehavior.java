package com.aicivilization.behavior;

import com.aicivilization.action.Crafting;
import com.aicivilization.action.ItemKinds;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.ItemValue;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.RelationshipData;
import java.util.List;
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
				if (get.quantity() <= 0 || get.itemId().equals(give.itemId()) || keepsBack(other, get.itemId())) {
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
			self.perceive(tick, "I tried to trade with " + otherName + ", but we had nothing the other wanted.", 0.2,
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
			self.perceive(tick, "I asked " + otherName + " for help, but they couldn't or wouldn't give me anything.", 0.35,
					Set.of(other.identity().id()));
			self.relationships().with(other.identity().id()).recordConversation(tick, -0.02, 0.0);
			log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
					selfName + " asked " + otherName + " for help" + (hungry ? " with food" : "") + " and was turned away.",
					List.of());
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

	/** Whether this agent could do with some help, or has something to trade. */
	static boolean inNeed(AgentMind mind) {
		return mind.needs().food() < 0.4;
	}

	private static double value(AgentMind mind, String itemId) {
		int owned = mind.countOf(itemId);
		int nutrition = ItemKinds.nutrition(itemId);
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
	private static boolean keepsBack(AgentMind mind, String itemId) {
		if (isTool(itemId)) {
			return mind.countOf(itemId) <= 1;
		}
		return ItemKinds.nutrition(itemId) > 0 && mind.needs().food() < 0.3 && mind.countOf(itemId) <= 1;
	}

	private record Deal(String give, int giveQty, String get, int getQty, double selfSurplus) {
	}
}
