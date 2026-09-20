package com.aicivilization.behavior;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.RelationshipData;

import java.util.List;

/**
 * Orchestrates one interaction between two embodied agents. Proximity (the
 * caller already knows the two entities are close) only makes interaction
 * <em>possible</em> — this is where each mind's own needs/personality/
 * relationship-toward-the-other decide whether anything happens, and if
 * so, for what purpose. Only {@code EXCHANGE_INFORMATION} has a real
 * effect in Milestone 1; the others are recognized outcomes of the same
 * evaluation, logged but not yet acted on (future work: trade/cooperation).
 */
public final class ConversationBehavior {

	private enum Purpose {
		EXCHANGE_INFORMATION,
		SOCIALIZE,
		REQUEST_HELP,
		OFFER_TRADE,
		AVOID
	}

	private ConversationBehavior() {
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

		Purpose purpose = choosePurpose(purposeRoll, relationship);
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
			case REQUEST_HELP, OFFER_TRADE -> log.append(tick, EventType.CONVERSATION,
					List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " tried to " + purpose.name().toLowerCase().replace('_', ' ')
							+ " with " + other.identity().name() + " (not yet implemented).",
					List.of());
			case AVOID -> {
			}
		}
	}

	private static Purpose choosePurpose(double r, RelationshipData relationship) {
		double trustBonus = relationship.trust() * 0.1;
		if (r < 0.55 + trustBonus) {
			return Purpose.EXCHANGE_INFORMATION;
		}
		if (r < 0.8) {
			return Purpose.SOCIALIZE;
		}
		if (r < 0.9) {
			return Purpose.REQUEST_HELP;
		}
		if (r < 0.97) {
			return Purpose.OFFER_TRADE;
		}
		return Purpose.AVOID;
	}

	private static void exchangeInformation(AgentMind self, AgentMind other, AgentEntity selfEntity,
			AgentEntity otherEntity, long tick, EventLog log) {
		List<MemoryEntry> candidates = self.memories().retrieve(tick, 5);
		if (candidates.isEmpty()) {
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		MemoryEntry shared = candidates.get(0);
		other.receiveTold(tick, self.identity().id(), self.identity().name(), shared);
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.02);

		log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
				self.identity().name() + " told " + other.identity().name() + ": " + shared.description(),
				List.of(Cause.memory(shared.id(), shared.description())));
		log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
				self.identity().name() + " and " + other.identity().name() + " talked.", List.of());
	}
}
