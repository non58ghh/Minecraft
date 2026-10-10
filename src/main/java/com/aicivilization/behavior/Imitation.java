package com.aicivilization.behavior;

import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.DesignGenerator;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.KnownDesign;
import com.aicivilization.mind.RelationshipData;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How building designs spread: by seeing someone's home standing there, or
 * by hearing someone describe theirs. Either way the copy may come out a
 * little different (the copier's memory isn't perfect, and a description
 * even less so), and how much the copier then wants to build it depends on
 * how much it trusts whoever it came from ({@link AgentMind#designToBuild}).
 */
final class Imitation {

	/** Close enough to take in the shape of a building. */
	private static final double SEE_DISTANCE_SQ = 14 * 14;
	/** Of those homes seen, how likely one is taken to heart, before liking adds to it. */
	private static final double BASE_SEE_CHANCE = 0.25;
	private static final double TELL_CHANCE = 0.3;

	private Imitation() {
	}

	/**
	 * Self has another agent in sight whose home is near: if the building is
	 * standing and self likes it (and its owner) enough, self learns it.
	 */
	static void lookAt(AgentEntity self, AgentMind mind, AgentMind owner, ServerLevel world, long tick, EventLog log) {
		if (owner.home().isEmpty() || owner == mind) {
			return;
		}
		Home home = owner.home().get();
		BlockPos origin = new BlockPos(home.x(), home.y(), home.z());
		Design design = home.design();
		if (self.blockPosition().distSqr(origin) <= SEE_DISTANCE_SQ) {
			// Whatever it thinks of the building, it now knows whose home stands here.
			mind.places().note(com.aicivilization.mind.Places.Kind.HOME, home.x(), home.y(), home.z(), tick,
					owner.identity().id(), false);
		}
		if (design.id().equals("hut") || knows(mind, design) || self.blockPosition().distSqr(origin) > SEE_DISTANCE_SQ) {
			return;
		}
		// It has to actually be there to be seen.
		if (PhysicalActions.damage(world, origin, design) > 0.25) {
			return;
		}
		RelationshipData toOwner = mind.relationships().with(owner.identity().id());
		Random random = new Random(tick ^ mind.identity().id().getLeastSignificantBits());
		double chance = BASE_SEE_CHANCE + Math.max(0, toOwner.affinity()) * 0.3 + mind.personality().curiosity() * 0.2;
		if (random.nextDouble() > chance) {
			// Noticed, but not taken with it: don't keep reconsidering the same building every look.
			remember(mind, design);
			return;
		}
		Design copy = DesignGenerator.drift(design, mind.identity().name(), random);
		mind.learnDesign(new KnownDesign(copy, "saw", owner.identity().name(), owner.identity().id(), tick));
		remember(mind, design);
		String ownerName = owner.identity().name();
		mind.perceive(tick, "I looked at " + ownerName + "'s " + design.kind() + " and worked out how to build one like it.",
				0.5, Set.of(owner.identity().id()));
		log.append(tick, EventType.ACTION, List.of(mind.identity().id(), owner.identity().id()),
				mind.identity().name() + " studied " + ownerName + "'s " + design.kind() + " and learned how to build one"
						+ (copy.id().equals(design.id()) ? "." : " (their own way)."), List.of());
	}

	/**
	 * In conversation, self may describe its home (or its idea for one) to
	 * other, who remembers it roughly. Returns whether it did.
	 */
	static boolean describeHome(AgentMind self, AgentMind other, long tick, double roll, EventLog log) {
		Design design = self.home().map(Home::design).orElse(null);
		if (design == null) {
			design = self.knownDesigns().stream().filter(k -> k.how().equals("designed")).map(KnownDesign::design)
					.findFirst().orElse(null);
		}
		if (design == null || design.id().equals("hut") || other.home().isPresent() || knows(other, design)
				|| roll > TELL_CHANCE) {
			return false;
		}
		Random random = new Random(tick ^ other.identity().id().getMostSignificantBits());
		// A description loses more than a look does: drift it twice.
		Design copy = DesignGenerator.drift(DesignGenerator.drift(design, other.identity().name(), random),
				other.identity().name(), random);
		other.learnDesign(new KnownDesign(copy, "told", self.identity().name(), self.identity().id(), tick));
		remember(other, design);
		String selfName = self.identity().name();
		other.perceive(tick, selfName + " described their " + design.kind() + " to me; I think I could build one.", 0.45,
				Set.of(self.identity().id()));
		log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
				selfName + " described their " + design.kind() + " to " + other.identity().name() + ".", List.of());
		return true;
	}

	private static boolean knows(AgentMind mind, Design design) {
		return mind.knownDesigns().stream().anyMatch(k -> k.design().id().equals(design.id()))
				|| SEEN.contains(mind.identity().id() + ">" + design.id());
	}

	/** Designs each agent has already considered, so it doesn't weigh the same building up every few seconds. Not saved. */
	private static final Set<String> SEEN = new java.util.HashSet<>();

	private static void remember(AgentMind mind, Design design) {
		if (SEEN.size() > 8192) {
			SEEN.clear();
		}
		SEEN.add(mind.identity().id() + ">" + design.id());
	}
}
