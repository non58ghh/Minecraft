package com.aicivilization.perception;

import com.aicivilization.mind.IntentType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

/**
 * A snapshot of what an agent's body can currently perceive in the real
 * Minecraft world, translated into the physical-affordance terms the
 * embodiment layer needs (what's physically possible right now), not raw
 * world data. This is what {@code AgentMind.decide} is given as
 * {@code availableIntents} — the mind decides what's <em>wanted</em>, this
 * decides what's <em>possible</em>.
 */
public record Surroundings(
		Set<IntentType> availableIntents,
		List<OtherAgentSighting> nearbyAgents,
		Optional<Animal> nearestAnimal,
		Optional<Player> nearestPlayer,
		Optional<Monster> nearestHostile
) {
	public record OtherAgentSighting(UUID agentId, String displayName, Entity entity) {
	}
}
