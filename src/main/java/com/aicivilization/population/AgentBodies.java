package com.aicivilization.population;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.UUID;

/** Placing agent bodies in the world: new ones, and replacements for lost ones. */
public final class AgentBodies {

	private AgentBodies() {
	}

	/**
	 * Moves {@code entity} to the first spot within a few blocks of
	 * {@code y} at ({@code x}, {@code z}) that has a solid block underneath
	 * and room to stand. Returns false (leaving the position unspecified)
	 * if there is none.
	 */
	public static boolean placeOnGround(ServerLevel world, AgentEntity entity, double x, double y, double z) {
		BlockPos start = BlockPos.containing(x, y, z);
		for (int dy = 2; dy >= -4; dy--) {
			BlockPos feet = start.above(dy);
			BlockPos below = feet.below();
			if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
				continue;
			}
			entity.setPos(x, feet.getY(), z);
			if (world.noCollision(entity)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gives each listed living mind a new body near the world spawn, with
	 * the mind's own id so it keeps its name, memories and relationships.
	 * For minds whose body is confirmed gone (lost to vanilla despawning
	 * before agents were made persistent). Returns how many were restored.
	 */
	public static int restore(ServerLevel world, List<AgentMind> minds) {
		BlockPos spawn = world.getRespawnData().pos();
		EventLog log = EventLog.get(world);
		PopulationRegistry registry = PopulationRegistry.get(world);
		int restored = 0;
		for (int i = 0; i < minds.size(); i++) {
			AgentMind mind = minds.get(i);
			UUID id = mind.identity().id();
			double angle = i * 2.399963; // golden angle: an even spread however many there are
			double radius = 3.0 + 1.2 * Math.sqrt(i);
			double x = spawn.getX() + 0.5 + Math.cos(angle) * radius;
			double z = spawn.getZ() + 0.5 + Math.sin(angle) * radius;
			world.getChunk(BlockPos.containing(x, 0, z)); // load it so the ground is there
			double y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));

			AgentEntity body = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
			body.setUUID(id);
			if (!placeOnGround(world, body, x, y, z)) {
				body.setPos(x, y, z);
			}
			if (!world.addFreshEntity(body)) {
				continue;
			}
			body.mind();
			registry.recordBodyChunk(id, body.chunkPosition().pack());
			log.append(world.getGameTime(), EventType.SPAWN, List.of(id),
					mind.identity().name() + " found their way back to the world after going missing.", List.of());
			restored++;
		}
		return restored;
	}

	/**
	 * Spawns {@code count} brand-new agents in a spread around the world
	 * spawn, each getting a fresh mind (and its SPAWN event) as it's created.
	 * Used to found the first settlement in an empty world. Returns how many
	 * were placed.
	 */
	public static int found(ServerLevel world, int count) {
		BlockPos spawn = world.getRespawnData().pos();
		PopulationRegistry registry = PopulationRegistry.get(world);
		int founded = 0;
		for (int i = 0; i < count; i++) {
			double angle = i * 2.399963; // golden angle, as in restore()
			double radius = 3.0 + 1.2 * Math.sqrt(i);
			double x = spawn.getX() + 0.5 + Math.cos(angle) * radius;
			double z = spawn.getZ() + 0.5 + Math.sin(angle) * radius;
			world.getChunk(BlockPos.containing(x, 0, z)); // load it so the ground is there
			double y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));

			AgentEntity body = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
			if (!placeOnGround(world, body, x, y, z)) {
				body.setPos(x, y, z);
			}
			if (!world.addFreshEntity(body)) {
				continue;
			}
			body.mind();
			registry.recordBodyChunk(body.getUUID(), body.chunkPosition().pack());
			founded++;
		}
		return founded;
	}
}
