package com.aicivilization.perception;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import java.util.Set;

/**
 * Which animals agents may hunt: ordinary livestock only. Pets, horses and
 * anything a player has named or tamed are never on the list, since agents
 * now really kill what they hunt.
 */
public final class Huntable {

	private static final Set<EntityType<?>> LIVESTOCK = Set.of(
			EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN, EntityTypes.RABBIT);

	private Huntable() {
	}

	public static boolean isHuntable(Animal animal) {
		return LIVESTOCK.contains(animal.getType())
				&& animal.isAlive()
				&& !animal.isBaby()
				&& !animal.hasCustomName();
	}
}
