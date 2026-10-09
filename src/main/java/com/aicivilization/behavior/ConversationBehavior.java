package com.aicivilization.behavior;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.RelationshipData;

import com.aicivilization.mind.Provenance;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates one interaction between two embodied agents. Proximity (the
 * caller already knows the two entities are close) only makes interaction
 * <em>possible</em> — this is where each mind's own needs/personality/
 * relationship-toward-the-other decide whether anything happens, and if
 * so, for what purpose: share news, pass the time, ask for help, or
 * trade (see {@link TradeBehavior}). A pair that has just talked won't
 * strike up the same conversation again for half a minute, and news is only
 * first-hand and only told to someone once, so gossip doesn't loop.
 */
public final class ConversationBehavior {

	private enum Purpose {
		EXCHANGE_INFORMATION,
		SOCIALIZE,
		REQUEST_HELP,
		OFFER_TRADE,
		AVOID
	}

	/** A given pair strikes up a conversation at most this often (half a minute). */
	private static final long PAIR_COOLDOWN_TICKS = 600;
	/** Last tick each (speaker, listener) pair talked. Not saved; it only paces conversation. */
	private static final Map<String, Long> LAST_TALK = new HashMap<>();
	/** What each speaker has already told each listener (by wording, so the same news isn't retold). Not saved. */
	private static final Map<String, Set<Long>> TOLD = new HashMap<>();

	private ConversationBehavior() {
	}

	/** Whether these two talked within the last half minute. */
	public static boolean recentlyTalked(UUID a, UUID b, long tick) {
		Long last = LAST_TALK.get(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
		return last != null && tick - last < PAIR_COOLDOWN_TICKS;
	}

	public static void attempt(AgentEntity selfEntity, AgentEntity otherEntity, long tick, EventLog log, double purposeRoll) {
		AgentMind self = selfEntity.mind();
		AgentMind other = otherEntity.mind();
		if (self == null || other == null || !self.isAlive() || !other.isAlive()) {
			return;
		}

		RelationshipData relationship = self.relationships().with(other.identity().id());
		double desire = (1.0 - self.needs().social()) * 0.6
				+ self.personality().sociability() * 0.3
				+ (relationship.affinity() + 1.0) / 2.0 * 0.1;

		if (desire < 0.2) {
			return; // AVOID: proximity alone did not produce an interaction.
		}

		// Either one starting it counts: the same two don't talk twice in half a minute.
		UUID a = self.identity().id();
		UUID b = other.identity().id();
		String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
		Long last = LAST_TALK.get(pair);
		if (last != null && tick - last < PAIR_COOLDOWN_TICKS) {
			// They've only just talked; being in each other's company still helps a little.
			self.needs().adjustSocial(0.05);
			return;
		}
		if (LAST_TALK.size() > 4096) {
			LAST_TALK.clear();
		}
		LAST_TALK.put(pair, tick);

		// A hungry person in front of someone with plenty: in conversation it shows, and a generous one shares.
		if (TradeBehavior.inNeed(other) && TradeBehavior.hasFoodToSpare(self)
				&& purposeRoll < 0.4 + self.personality().sociability() * 0.5) {
			TradeBehavior.offerFood(self, other, tick, log);
			self.needs().adjustSocial(0.08);
			other.needs().adjustSocial(0.08);
			return;
		}
		if (CoBuilding.talk(self, other, tick, purposeRoll, log)) {
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.08);
			return;
		}
		Purpose purpose = choosePurpose(purposeRoll, relationship, TradeBehavior.inNeed(self), !self.possessions().isEmpty());
		switch (purpose) {
			case EXCHANGE_INFORMATION -> exchangeInformation(self, other, selfEntity, otherEntity, tick, log);
			case SOCIALIZE -> {
				self.needs().adjustSocial(0.15);
				other.needs().adjustSocial(0.1);
				relationship.recordConversation(tick, 0.03, 0.01);
				log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
						self.identity().name() + " and " + other.identity().name() + " talked for a while.",
						List.of());
			}
			case REQUEST_HELP -> {
				TradeBehavior.requestHelp(self, other, tick, log);
				self.needs().adjustSocial(0.08);
				other.needs().adjustSocial(0.05);
			}
			case OFFER_TRADE -> {
				TradeBehavior.offerTrade(self, other, tick, log);
				self.needs().adjustSocial(0.08);
				other.needs().adjustSocial(0.05);
			}
			case AVOID -> {
			}
		}
	}

	/**
	 * What the conversation is for. Someone in need asks for help far more
	 * often; someone with things to offer may propose a trade; trust makes
	 * sharing news more likely.
	 */
	private static Purpose choosePurpose(double r, RelationshipData relationship, boolean inNeed, boolean hasGoods) {
		double info = 0.4 + relationship.trust() * 0.1;
		double social = 0.25;
		double help = inNeed ? 0.35 : 0.03;
		double trade = hasGoods ? 0.2 : 0.0;
		double avoid = 0.03;
		double roll = r * (info + social + help + trade + avoid);
		if ((roll -= info) < 0) {
			return Purpose.EXCHANGE_INFORMATION;
		}
		if ((roll -= social) < 0) {
			return Purpose.SOCIALIZE;
		}
		if ((roll -= help) < 0) {
			return Purpose.REQUEST_HELP;
		}
		if ((roll -= trade) < 0) {
			return Purpose.OFFER_TRADE;
		}
		return Purpose.AVOID;
	}

	private static void exchangeInformation(AgentMind self, AgentMind other, AgentEntity selfEntity,
			AgentEntity otherEntity, long tick, EventLog log) {
		// Talk of home: how one's own house is built, which the other may take up.
		if (Imitation.describeHome(self, other, tick, selfEntity.getRandom().nextDouble(), log)) {
			self.relationships().with(other.identity().id()).recordConversation(tick, 0.03, 0.02);
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// Only first-hand news (no retelling what someone else said), and nothing this listener has heard from us.
		String pair = self.identity().id() + ">" + other.identity().id();
		Set<Long> alreadyTold = TOLD.computeIfAbsent(pair, k -> new HashSet<>());
		MemoryEntry shared = null;
		for (MemoryEntry candidate : self.memories().retrieve(tick, 8)) {
			if (!(candidate.provenance() instanceof Provenance.Told)
					&& !alreadyTold.contains((long) candidate.description().hashCode())
					&& !candidate.participants().contains(other.identity().id())) {
				shared = candidate;
				break;
			}
		}
		if (shared == null) {
			// Nothing new to tell: just pass the time.
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.0);
			log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " and " + other.identity().name() + " talked for a while.", List.of());
			return;
		}
		if (TOLD.size() > 4096) {
			TOLD.clear();
		}
		alreadyTold.add((long) shared.description().hashCode());
		other.receiveTold(tick, self.identity().id(), self.identity().name(), shared);
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.02);

		log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
				self.identity().name() + " told " + other.identity().name() + ": " + shared.description(),
				List.of(Cause.memory(shared.id(), shared.description())));
		self.needs().adjustSocial(0.1);
		other.needs().adjustSocial(0.05);
	}
}
