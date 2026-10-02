package com.aicivilization.perception;

import com.aicivilization.mind.IntentType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Gathers what a physically-embodied agent can currently perceive from the
 * real Minecraft world. This is the <em>only</em> Minecraft-facing system
 * that's allowed to originate facts an {@code AgentMind} treats as directly
 * observed ({@code Provenance.Perceived}) — nothing else may hand a mind a
 * fact about the world.
 */
public final class PerceptionSystem {

	private static final double FOOD_RADIUS = 10.0;
	private static final double SAFETY_RADIUS = 14.0;
	private static final double SOCIAL_RADIUS = 12.0;

	public Surroundings perceive(Entity self, Level world) {
		UUID selfId = self.getUUID();

		AABB foodBox = AABB.ofSize(self.position(), FOOD_RADIUS * 2, FOOD_RADIUS * 2, FOOD_RADIUS * 2);
		List<Animal> animals = world.getEntitiesOfClass(Animal.class, foodBox, Huntable::isHuntable);
		Optional<Animal> nearestAnimal = nearest(self, animals);

		AABB safetyBox = AABB.ofSize(self.position(), SAFETY_RADIUS * 2, SAFETY_RADIUS * 2, SAFETY_RADIUS * 2);
		List<Monster> hostiles = world.getEntitiesOfClass(Monster.class, safetyBox, e -> true);
		Optional<Monster> nearestHostile = nearest(self, hostiles);

		AABB socialBox = AABB.ofSize(self.position(), SOCIAL_RADIUS * 2, SOCIAL_RADIUS * 2, SOCIAL_RADIUS * 2);
		List<Player> players = world.getEntitiesOfClass(Player.class, socialBox, e -> !e.isSpectator());
		Optional<Player> nearestPlayer = nearest(self, players);

		List<Entity> embodiedNearby = world.getEntitiesOfClass(Entity.class, socialBox,
				e -> e instanceof Embodied && !e.getUUID().equals(selfId));
		List<Surroundings.OtherAgentSighting> sightings = new ArrayList<>();
		for (Entity entity : embodiedNearby) {
			Embodied embodied = (Embodied) entity;
			sightings.add(new Surroundings.OtherAgentSighting(embodied.agentId(), embodied.agentDisplayName(), entity));
		}

		Set<IntentType> available = EnumSet.of(IntentType.EXPLORE, IntentType.REST, IntentType.IDLE);
		if (nearestAnimal.isPresent()) {
			available.add(IntentType.FORAGE_FOOD);
		}
		if (nearestHostile.isPresent()) {
			available.add(IntentType.SEEK_SAFETY);
		}
		if (!sightings.isEmpty() || nearestPlayer.isPresent()) {
			available.add(IntentType.SOCIALIZE);
		}

		return new Surroundings(available, sightings, nearestAnimal, nearestPlayer, nearestHostile);
	}

	private static <T extends Entity> Optional<T> nearest(Entity self, List<T> candidates) {
		return candidates.stream().min(Comparator.comparingDouble(self::distanceToSqr));
	}
}
