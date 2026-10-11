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
	/**
	 * Up to {@code count} random offsets within {@code spread} of the centre,
	 * each at least {@code apart} from the rest and passing {@code ok} (dry
	 * land). Fewer if the land won't fit them. Pure, for testing.
	 */
	static List<double[]> scatter(int count, int spread, int apart, java.util.Random random,
			java.util.function.BiPredicate<Double, Double> ok) {
		return scatter(count, spread, apart, random, List.of(), ok, Integer.MAX_VALUE);
	}

	/**
	 * As above, also keeping clear of spots already {@code taken}, and
	 * testing at most {@code maxTests} spots with {@code ok} (each test may
	 * mean generating land, so few are tried at a time).
	 */
	static List<double[]> scatter(int count, int spread, int apart, java.util.Random random, List<double[]> taken,
			java.util.function.BiPredicate<Double, Double> ok, int maxTests) {
		List<double[]> spots = new java.util.ArrayList<>();
		long apartSq = (long) apart * apart;
		int tests = 0;
		for (int attempt = 0; attempt < (count + taken.size()) * 20 && spots.size() < count && tests < maxTests; attempt++) {
			double r = spread * Math.sqrt(random.nextDouble());
			double a = random.nextDouble() * Math.PI * 2;
			double x = Math.cos(a) * r, z = Math.sin(a) * r;
			boolean clear = java.util.stream.Stream.concat(spots.stream(), taken.stream())
					.allMatch(s -> (s[0] - x) * (s[0] - x) + (s[1] - z) * (s[1] - z) >= apartSq);
			if (clear) {
				tests++;
				if (ok.test(x, z)) {
					spots.add(new double[] {x, z});
				}
			}
		}
		return spots;
	}

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
		return foundTogether(world, count);
	}

	/**
	 * Places one founder at a random spot within {@code spread} blocks of
	 * the world spawn, at least {@code apart} from every living agent and
	 * not in water, trying up to three spots: called every second until all are
	 * placed, since each spot may mean generating new land. Returns whether
	 * one was placed.
	 */
	public static boolean foundOne(ServerLevel world, int spread, int apart) {
		BlockPos spawn = world.getRespawnData().pos();
		PopulationRegistry registry = PopulationRegistry.get(world);
		java.util.Random random = new java.util.Random(world.getRandom().nextLong());
		List<double[]> taken = new java.util.ArrayList<>();
		for (AgentMind mind : registry.population().allMinds()) {
			Long chunk = registry.bodyChunks().get(mind.identity().id());
			if (mind.isAlive() && chunk != null) {
				net.minecraft.world.level.ChunkPos at = net.minecraft.world.level.ChunkPos.unpack(chunk);
				taken.add(new double[] {at.getMiddleBlockX() - spawn.getX(), at.getMiddleBlockZ() - spawn.getZ()});
			}
		}
		List<double[]> spot = scatter(1, spread, apart, random, taken, (x, z) -> {
			int bx = (int) Math.floor(spawn.getX() + x), bz = (int) Math.floor(spawn.getZ() + z);
			world.getChunk(bx >> 4, bz >> 4); // load (or generate) it so the ground is there
			BlockPos top = new BlockPos(bx, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz);
			return world.getFluidState(top.below()).isEmpty() && world.getFluidState(top).isEmpty();
		}, 3);
		if (spot.isEmpty()) {
			return false;
		}
		double x = spawn.getX() + 0.5 + spot.get(0)[0], z = spawn.getZ() + 0.5 + spot.get(0)[1];
		double y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		AgentEntity body = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
		if (!placeOnGround(world, body, x, y, z)) {
			body.setPos(x, y, z);
		}
		if (!world.addFreshEntity(body)) {
			return false;
		}
		body.mind();
		registry.recordBodyChunk(body.getUUID(), body.chunkPosition().pack());
		return true;
	}

	/**
	 * Spawns {@code count} brand-new agents together around the world spawn.
	 */
	public static int foundTogether(ServerLevel world, int count) {
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
