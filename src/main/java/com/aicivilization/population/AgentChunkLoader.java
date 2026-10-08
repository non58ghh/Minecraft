package com.aicivilization.population;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.mind.AgentMind;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunk each living agent's body is in loaded while the
 * simulation runs, so agents act with nobody online. Minecraft no longer
 * keeps spawn chunks loaded, so without this an empty server unloads every
 * agent body and nothing happens. Outside running time the chunks are
 * released, and bodies unload until the next window.
 *
 * <p>Uses vanilla forced chunks. Only chunks this class forced are ever
 * released, tracked in {@link PopulationRegistry#forcedChunks()}.
 */
public final class AgentChunkLoader {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");

	private AgentChunkLoader() {
	}

	/**
	 * Once at startup: finds the saved chunk of every living agent whose body
	 * location was never recorded, by scanning each dimension's entity files.
	 */
	public static void locateUnknownBodies(MinecraftServer server) {
		Path root = server.getWorldPath(LevelResource.ROOT);
		for (ServerLevel world : server.getAllLevels()) {
			PopulationRegistry registry = PopulationRegistry.get(world);
			Set<UUID> unknown = new HashSet<>();
			for (AgentMind mind : registry.population().allMinds()) {
				UUID id = mind.identity().id();
				if (mind.isAlive() && !registry.bodyChunks().containsKey(id)) {
					unknown.add(id);
				}
			}
			if (unknown.isEmpty()) {
				continue;
			}
			Identifier dim = world.dimension().identifier();
			Path entities = root.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath()).resolve("entities");
			Map<UUID, Long> found = EntityRegionScanner.find(entities, unknown);
			found.forEach(registry::recordBodyChunk);
			LOGGER.info("AI Civilization: located {} of {} agent bodies with no recorded position in {}.",
					found.size(), unknown.size(), dim);
		}
	}

	/** Call about once a second on the server thread. */
	public static void update(ServerLevel world, boolean running) {
		PopulationRegistry registry = PopulationRegistry.get(world);
		Population population = registry.population();

		for (AgentEntity body : world.getEntities(AICivilizationMod.AGENT_ENTITY_TYPE, e -> true)) {
			registry.recordBodyChunk(body.getUUID(), body.chunkPosition().pack());
		}

		Set<Long> wanted = new HashSet<>();
		if (running) {
			for (Map.Entry<UUID, Long> e : registry.bodyChunks().entrySet()) {
				if (population.getMind(e.getKey()).map(AgentMind::isAlive).orElse(false)) {
					wanted.add(e.getValue());
				}
			}
		}

		Set<Long> forced = registry.forcedChunks();
		boolean changed = false;
		for (long chunk : wanted) {
			if (forced.add(chunk)) {
				world.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
				changed = true;
			}
		}
		var it = forced.iterator();
		while (it.hasNext()) {
			long chunk = it.next();
			if (!wanted.contains(chunk)) {
				world.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
				it.remove();
				changed = true;
			}
		}
		if (changed) {
			registry.setDirty();
		}
	}
}
