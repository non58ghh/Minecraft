package com.aicivilization.population;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.RelationshipData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Children. Two grown agents who are fond of and trust each other, living
 * together at a home, fed and not afraid, may now and then have a child.
 * Nobody decides to; it happens to those whose lives have come to that, as
 * often as chance has it. The child takes after both parents, a little of
 * each and a little its own, and is born at their home with nothing. How it
 * fares from there is down to them: it can't hunt, fight or build while it
 * grows, so it eats what it's given and is as safe as where they take it.
 */
public final class Births {

	/** Fond of each other at least this much, both ways. */
	static final double FONDNESS = 0.5;
	static final double TRUST = 0.3;
	/** Within this of each other, and of the home. */
	static final double TOGETHER = 8, AT_HOME = 16;
	static final double FED = 0.5, UNAFRAID = 0.3;
	/** Chance per check (every {@link Wanderers#CHECK_EVERY_TICKS}) for a couple whose life allows it. */
	static final double CHANCE = 0.03;
	/** No second child while the last is this young. */
	static final long SPACING = 4 * 24000L;
	/** How far a child's nature strays from its parents' average. */
	private static final double NATURE_DRIFT = 0.15;

	private Births() {
	}

	/** Whether these two, as they are now, could have a child (chance aside). Pure, for testing. */
	static boolean couldHaveChild(AgentMind a, AgentMind b, long tick, double apart, double aFromHome, double bFromHome,
			boolean youngChild) {
		if (!a.isAlive() || !b.isAlive() || a.isChild(tick) || b.isChild(tick) || youngChild) {
			return false;
		}
		if (apart > TOGETHER || aFromHome > AT_HOME || bFromHome > AT_HOME) {
			return false;
		}
		if (a.parents().contains(b.identity().id()) || b.parents().contains(a.identity().id())
				|| (!a.parents().isEmpty() && a.parents().equals(b.parents()))) {
			return false;
		}
		RelationshipData ab = a.relationships().get(b.identity().id()).orElse(null);
		RelationshipData ba = b.relationships().get(a.identity().id()).orElse(null);
		return ab != null && ba != null && ab.affinity() >= FONDNESS && ba.affinity() >= FONDNESS
				&& ab.trust() >= TRUST && ba.trust() >= TRUST
				&& a.needs().food() >= FED && b.needs().food() >= FED
				&& a.needs().safety() >= UNAFRAID && b.needs().safety() >= UNAFRAID;
	}

	/** A child's nature: its parents' average, give or take. */
	static Personality blend(Personality a, Personality b, Random random) {
		return new Personality(
				mix(a.curiosity(), b.curiosity(), random), mix(a.risk(), b.risk(), random),
				mix(a.sociability(), b.sociability(), random), mix(a.ambition(), b.ambition(), random));
	}

	private static double mix(double a, double b, Random random) {
		return (a + b) / 2 + (random.nextDouble() * 2 - 1) * NATURE_DRIFT;
	}

	/** Every {@link Wanderers#CHECK_EVERY_TICKS} while the simulation runs. */
	public static void tick(ServerLevel world, int maxAgents) {
		PopulationRegistry registry = PopulationRegistry.get(world);
		long tick = world.getGameTime();
		Random random = new Random(world.getRandom().nextLong());
		List<AgentMind> living = registry.population().allMinds().stream().filter(AgentMind::isAlive).toList();
		if (maxAgents > 0 && living.size() >= maxAgents) {
			return;
		}
		Set<UUID> busy = new HashSet<>();
		for (int i = 0; i < living.size(); i++) {
			for (int j = i + 1; j < living.size(); j++) {
				AgentMind a = living.get(i), b = living.get(j);
				if (busy.contains(a.identity().id()) || busy.contains(b.identity().id())) {
					continue;
				}
				if (!(world.getEntity(a.identity().id()) instanceof AgentEntity ea)
						|| !(world.getEntity(b.identity().id()) instanceof AgentEntity eb)) {
					continue;
				}
				Optional<Home> home = a.home().or(b::home);
				if (home.isEmpty()) {
					continue;
				}
				BlockPos origin = new BlockPos(home.get().x(), home.get().y(), home.get().z());
				boolean youngChild = living.stream().anyMatch(c ->
						(c.parents().contains(a.identity().id()) || c.parents().contains(b.identity().id()))
								&& c.identity().ageInTicks(tick) < SPACING);
				if (couldHaveChild(a, b, tick, ea.distanceTo(eb), Math.sqrt(ea.blockPosition().distSqr(origin)),
						Math.sqrt(eb.blockPosition().distSqr(origin)), youngChild) && random.nextDouble() < CHANCE) {
					busy.add(a.identity().id());
					busy.add(b.identity().id());
					born(world, registry, a, b, ea, random, tick);
				}
			}
		}
	}

	private static void born(ServerLevel world, PopulationRegistry registry, AgentMind a, AgentMind b, AgentEntity near,
			Random random, long tick) {
		Set<String> taken = new HashSet<>();
		registry.population().allMinds().forEach(m -> taken.add(m.identity().name()));
		UUID id = UUID.randomUUID();
		String name = AICivilizationMod.randomAgentName(random, taken);
		AgentMind child = registry.createMind(id, name, tick, blend(a.personality(), b.personality(), random));
		child.setParents(List.of(a.identity().id(), b.identity().id()));
		AgentEntity body = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
		body.setUUID(id);
		if (!AgentBodies.placeOnGround(world, body, near.getX(), near.getY(), near.getZ())) {
			body.setPos(near.getX(), near.getY(), near.getZ());
		}
		if (!world.addFreshEntity(body)) {
			registry.recordDeath(id);
			return;
		}
		body.mind();
		body.growUp(tick);
		registry.recordBodyChunk(id, body.chunkPosition().pack());
		for (AgentMind parent : List.of(a, b)) {
			parent.relationships().with(id).recordConversation(tick, 0.8, 0.8);
			child.relationships().with(parent.identity().id()).recordConversation(tick, 0.8, 0.8);
			AgentMind other = parent == a ? b : a;
			parent.perceive(tick, name + " was born to " + other.identity().name() + " and me.", 0.9,
					Set.of(id, other.identity().id()));
		}
		child.perceive(tick, "I was born to " + a.identity().name() + " and " + b.identity().name() + ".", 0.9,
				Set.of(a.identity().id(), b.identity().id()));
		EventLog.get(world).append(tick, EventType.MILESTONE, new ArrayList<>(List.of(a.identity().id(), b.identity().id(), id)),
				a.identity().name() + " and " + b.identity().name() + " had a child: " + name + ".", List.of());
	}
}
