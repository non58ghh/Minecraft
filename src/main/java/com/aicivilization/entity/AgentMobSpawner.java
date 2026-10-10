package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Monsters come to agents the way they come to players. Vanilla only spawns
 * hostile mobs near a player, so with nobody online an agent's nights were
 * perfectly safe. This spawns them near agents by the game's own rules:
 * only where it's dark enough, only kinds the biome spawns, only where that
 * kind may stand, never closer than {@link #MIN_DISTANCE}, and only while
 * the local count is under a cap like vanilla's. Where a player is near,
 * vanilla's own spawning covers it and this stays out of the way.
 *
 * <p>Vanilla despawns monsters by distance from players, and with none
 * online it never would; the ones spawned here are despawned the same way
 * by distance from agents (and players): at once beyond 128 blocks, now
 * and then beyond 32.
 */
public final class AgentMobSpawner {

	/** Marks a monster this class spawned, so only those are despawned here. */
	static final String TAG = "aiciv_spawned";
	/** Run this often (ticks). */
	public static final int EVERY_TICKS = 20;
	/** Spawn no closer to the agent than this, and no further than this (within the chunks kept ticking around it). */
	static final int MIN_DISTANCE = 16;
	static final int MAX_DISTANCE = 28;
	/** Monsters already within this of an agent count toward its cap. */
	static final double CAP_RADIUS = 64;
	/** At most this many monsters around one agent (vanilla's cap is about 70 across a player's 17x17 chunks). */
	static final int CAP = 8;
	/** A player this close means vanilla's spawning is already at work here. */
	static final double PLAYER_RANGE = 128;
	static final double DESPAWN_NOW = 128;
	static final double DESPAWN_SOMETIMES = 32;
	/** Chance per run of despawning one between 32 and 128 blocks from everyone (vanilla: 1/800 a tick). */
	static final double DESPAWN_CHANCE = EVERY_TICKS / 800.0;

	private AgentMobSpawner() {
	}

	/** Every {@link #EVERY_TICKS} on the server thread. */
	public static void tick(ServerLevel world) {
		if (world.getDifficulty() == Difficulty.PEACEFUL || !world.getGameRules().get(GameRules.SPAWN_MOBS)
				|| !world.getGameRules().get(GameRules.SPAWN_MONSTERS)) {
			return;
		}
		RandomSource random = world.getRandom();
		List<? extends AgentEntity> agents = world.getEntities(AICivilizationMod.AGENT_ENTITY_TYPE, e -> e.isAlive());
		for (AgentEntity agent : agents) {
			if (world.getNearestPlayer(agent, PLAYER_RANGE) != null) {
				continue;
			}
			AABB around = agent.getBoundingBox().inflate(CAP_RADIUS);
			if (world.getEntitiesOfClass(Monster.class, around, m -> m.isAlive()).size() >= CAP) {
				continue;
			}
			trySpawn(world, agent, random);
		}
		despawn(world, agents, random);
	}

	/** One attempt, as vanilla makes for each chunk: a spot, a kind the biome spawns there, its own rules. */
	private static void trySpawn(ServerLevel world, AgentEntity agent, RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2;
		double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
		int x = (int) Math.floor(agent.getX() + Math.cos(angle) * distance);
		int z = (int) Math.floor(agent.getZ() + Math.sin(angle) * distance);
		if (world.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
			return;
		}
		BlockPos pos = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		if (!world.isPositionEntityTicking(pos)) {
			return;
		}
		MobSpawnSettings.SpawnerData data = world.getBiome(pos).value().getMobSettings().getMobs(MobCategory.MONSTER)
				.getRandom(random).orElse(null);
		if (data == null) {
			return;
		}
		EntityType<?> type = data.type();
		if (!SpawnPlacements.isSpawnPositionOk(type, world, pos)
				|| !SpawnPlacements.checkSpawnRules(type, world, EntitySpawnReason.NATURAL, pos, random)) {
			return;
		}
		Entity created = type.create(world, EntitySpawnReason.NATURAL);
		if (!(created instanceof Mob mob)) {
			if (created != null) {
				created.discard();
			}
			return;
		}
		mob.snapTo(x + 0.5, pos.getY(), z + 0.5, random.nextFloat() * 360f, 0f);
		if (!mob.checkSpawnRules(world, EntitySpawnReason.NATURAL) || !mob.checkSpawnObstruction(world)) {
			mob.discard();
			return;
		}
		mob.finalizeSpawn(world, world.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
		mob.addTag(TAG);
		world.addFreshEntityWithPassengers(mob);
	}

	/** Despawns monsters spawned here that nobody, agent or player, is near: as vanilla does near players. */
	private static void despawn(ServerLevel world, List<? extends AgentEntity> agents, RandomSource random) {
		for (Monster monster : world.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Monster.class),
				m -> m.entityTags().contains(TAG))) {
			if (monster.isPersistenceRequired() || monster.hasCustomName()) {
				continue;
			}
			double nearest = Double.MAX_VALUE;
			for (AgentEntity agent : agents) {
				nearest = Math.min(nearest, monster.distanceToSqr(agent));
			}
			Player player = world.getNearestPlayer(monster, -1);
			if (player != null) {
				nearest = Math.min(nearest, monster.distanceToSqr(player));
			}
			if (nearest > DESPAWN_NOW * DESPAWN_NOW
					|| nearest > DESPAWN_SOMETIMES * DESPAWN_SOMETIMES && random.nextDouble() < DESPAWN_CHANCE) {
				monster.discard();
			}
		}
	}
}
