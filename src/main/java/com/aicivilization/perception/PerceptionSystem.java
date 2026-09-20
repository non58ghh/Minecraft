package com.aicivilization.perception;

import com.aicivilization.mind.IntentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

	public Surroundings perceive(Entity self, World world) {
		UUID selfId = self.getUuid();

		Box foodBox = Box.of(self.getPos(), FOOD_RADIUS * 2, FOOD_RADIUS * 2, FOOD_RADIUS * 2);
		List<AnimalEntity> animals = world.getEntitiesByClass(AnimalEntity.class, foodBox, e -> true);
		Optional<AnimalEntity> nearestAnimal = nearest(self, animals);

		Box safetyBox = Box.of(self.getPos(), SAFETY_RADIUS * 2, SAFETY_RADIUS * 2, SAFETY_RADIUS * 2);
		List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class, safetyBox, e -> true);
		Optional<HostileEntity> nearestHostile = nearest(self, hostiles);

		Box socialBox = Box.of(self.getPos(), SOCIAL_RADIUS * 2, SOCIAL_RADIUS * 2, SOCIAL_RADIUS * 2);
		List<PlayerEntity> players = world.getEntitiesByClass(PlayerEntity.class, socialBox, e -> !e.isSpectator());
		Optional<PlayerEntity> nearestPlayer = nearest(self, players);

		List<Entity> embodiedNearby = world.getEntitiesByClass(Entity.class, socialBox,
				e -> e instanceof Embodied && !e.getUuid().equals(selfId));
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
		return candidates.stream().min(Comparator.comparingDouble(self::squaredDistanceTo));
	}
}
