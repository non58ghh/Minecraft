package com.aicivilization.behavior;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Project;
import com.aicivilization.mind.RelationshipData;
import java.util.List;
import java.util.Set;

/**
 * Building a home together, agreed face to face. Two agents without a home
 * who trust each other may agree to build one between them: the one the
 * proposer has already started, or else the proposer's preferred design at
 * wherever one of them finds a good spot first (the other hears where when
 * they next meet), blocks from both, and a
 * shared home at the end. Whether the other agrees depends on how much it
 * trusts the one asking and how sociable it is.
 */
final class CoBuilding {

	/** Trust each needs in the other before building together is on the table. */
	private static final double TRUST_NEEDED = 0.2;
	/** How often, of conversations where it's possible, it's actually suggested. */
	private static final double PROPOSE_CHANCE = 0.35;

	private CoBuilding() {
	}

	/**
	 * Called at the start of a conversation. Handles project talk (passing on
	 * where the site is, or proposing to build together) and returns whether
	 * that was what the conversation was about.
	 */
	static boolean talk(AgentMind self, AgentMind other, long tick, double roll, EventLog log) {
		if (self.project().isPresent() && self.project().get().partner().equals(other.identity().id())) {
			return shareSite(self, other, tick, log);
		}
		if (self.home().isPresent() || other.home().isPresent() || self.project().isPresent() || other.project().isPresent()
				|| other.isBuilding() || roll > PROPOSE_CHANCE) {
			return false;
		}
		RelationshipData selfToOther = self.relationships().with(other.identity().id());
		RelationshipData otherToSelf = other.relationships().with(self.identity().id());
		if (selfToOther.trust() < TRUST_NEEDED || otherToSelf.trust() < TRUST_NEEDED) {
			return false;
		}
		String selfName = self.identity().name();
		String otherName = other.identity().name();
		// Already putting one up: the invitation is to help finish it and share it.
		var underway = self.buildingSite();
		Design design = underway.map(com.aicivilization.mind.Home::design).orElseGet(() -> self.designToBuild().design());
		double willing = otherToSelf.trust() + other.personality().sociability() * 0.5 + (1.0 - other.needs().belonging()) * 0.3;
		if (willing < 0.6) {
			self.perceive(tick, "I asked " + otherName + " to build a home with me; they'd rather not.", 0.4,
					Set.of(other.identity().id()));
			other.perceive(tick, selfName + " asked me to build a home with them; I said no.", 0.3, Set.of(self.identity().id()));
			log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
					selfName + " asked " + otherName + " to build a home together; " + otherName + " declined.", List.of());
			return true;
		}
		Project forSelf = Project.agreed(other.identity().id(), otherName, design, tick);
		Project forOther = Project.agreed(self.identity().id(), selfName, design, tick);
		if (underway.isPresent()) {
			var site = underway.get();
			forSelf = forSelf.at(site.x(), site.y(), site.z());
			forOther = forOther.at(site.x(), site.y(), site.z());
		}
		self.setProject(forSelf);
		other.setProject(forOther);
		self.perceive(tick, otherName + " and I agreed to build " + article(design.kind()) + " together.", 0.7,
				Set.of(other.identity().id()));
		other.perceive(tick, selfName + " and I agreed to build " + article(design.kind()) + " together, to " + selfName
				+ "'s design.", 0.7, Set.of(self.identity().id()));
		selfToOther.recordConversation(tick, 0.08, 0.05);
		otherToSelf.recordConversation(tick, 0.08, 0.05);
		log.append(tick, EventType.MILESTONE, List.of(self.identity().id(), other.identity().id()),
				selfName + " and " + otherName + " agreed to build " + article(design.kind()) + " together.", List.of());
		return true;
	}

	/** Partners catching up: whoever knows where the house is going tells the other. */
	private static boolean shareSite(AgentMind self, AgentMind other, long tick, EventLog log) {
		Project mine = self.project().get();
		Project theirs = other.project().orElse(null);
		if (!mine.siteKnown() || theirs == null || theirs.siteKnown() || !theirs.partner().equals(self.identity().id())) {
			return false;
		}
		other.setProject(theirs.at(mine.x(), mine.y(), mine.z()));
		other.perceive(tick, self.identity().name() + " showed me where we're building our " + mine.design().kind() + ".", 0.5,
				Set.of(self.identity().id()));
		log.append(tick, EventType.CONVERSATION, List.of(self.identity().id(), other.identity().id()),
				self.identity().name() + " told " + other.identity().name() + " where they're building their home.", List.of());
		return true;
	}

	static String article(String kind) {
		return ("aeiou".indexOf(Character.toLowerCase(kind.charAt(0))) >= 0 ? "an " : "a ") + kind;
	}
}
