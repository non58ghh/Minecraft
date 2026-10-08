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

	/** What the startup scan found, shown on the observer page for diagnosis. */
	private static volatile String scanStatus = "not run yet";

	public static String scanStatus() {
		return scanStatus;
	}

	private AgentChunkLoader() {
	}

	/**
	 * Once at startup: finds the saved chunk of every living agent whose body
	 * location was never recorded, by scanning each dimension's entity files.
	 */
	public static void locateUnknownBodies(MinecraftServer server) {
		Path root = server.getWorldPath(LevelResource.ROOT);
		StringBuilder status = new StringBuilder();
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
			EntityRegionScanner.Result result = EntityRegionScanner.scan(entities, unknown);
			if (!result.dirExists()) {
				// Worlds from before the dimensions/ layout keep entities in the old places.
				Path legacy = switch (dim.toString()) {
					case "minecraft:overworld" -> root.resolve("entities");
					case "minecraft:the_nether" -> root.resolve("DIM-1").resolve("entities");
					case "minecraft:the_end" -> root.resolve("DIM1").resolve("entities");
					default -> entities;
				};
				if (!legacy.equals(entities)) {
					entities = legacy;
					result = EntityRegionScanner.scan(entities, unknown);
				}
			}
			result.found().forEach(registry::recordBodyChunk);
			String line = String.format("%s: located %d of %d bodies (%s; %d region files, %d chunks read, %d unreadable)",
					dim.getPath(), result.found().size(), unknown.size(),
					result.dirExists() ? root.relativize(entities) : "no entities folder",
					result.regionFiles(), result.chunksRead(), result.chunksUnreadable());
			LOGGER.info("AI Civilization body scan: {}", line);
			status.append(status.isEmpty() ? "" : "; ").append(line);
		}
		scanStatus = status.isEmpty() ? "every living agent already had a recorded position" : status.toString();
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
