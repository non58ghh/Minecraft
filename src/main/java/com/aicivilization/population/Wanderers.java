package com.aicivilization.population;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Now and then someone new wanders in from far away: one alone, or two or
 * three together. When is never on a schedule anyone could learn: the wait
 * until the next arrival is drawn afresh each time, from a spread that is
 * itself drawn afresh (sometimes a few hours of game time, sometimes a
 * couple of weeks). They come in out of sight of everyone, somewhere out
 * in the land beyond the settlement, at whatever time of day it happens to
 * be, and start out as the founders did, with nothing but themselves.
 * Even a world everyone has died out of gets newcomers in time.
 */
public final class Wanderers extends SavedData {

	private static final Codec<Wanderers> CODEC = CompoundTag.CODEC.xmap(Wanderers::fromTag, Wanderers::toTag);

	public static final SavedDataType<Wanderers> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "wanderers"),
			Wanderers::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	static final long DAY = 24000;
	/** The typical wait, itself drawn anew each time, lies between these. */
	static final double MIN_MEAN_DAYS = 1.0, MAX_MEAN_DAYS = 6.0;
	/** No wait shorter than this or longer than this. */
	static final long MIN_GAP = DAY / 4, MAX_GAP = 15 * DAY;
	/** How far from the nearest settler they turn up. */
	static final int MIN_DISTANCE = 80, MAX_DISTANCE = 200;
	public static final int CHECK_EVERY_TICKS = 1200;

	private long nextArrival = -1;

	public static Wanderers get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Ticks until the next arrival: exponential around a mean that is random too, so no rhythm emerges. */
	static long nextGap(Random random) {
		double meanDays = MIN_MEAN_DAYS + random.nextDouble() * (MAX_MEAN_DAYS - MIN_MEAN_DAYS);
		double gap = -Math.log(1.0 - random.nextDouble()) * meanDays * DAY;
		return Math.max(MIN_GAP, Math.min(MAX_GAP, (long) gap));
	}

	/** Mostly one alone; sometimes two or three who travelled together. */
	static int partySize(Random random) {
		double r = random.nextDouble();
		return r < 0.7 ? 1 : r < 0.92 ? 2 : 3;
	}

	/** Every {@link #CHECK_EVERY_TICKS} while the simulation runs. */
	public static void tick(ServerLevel world, int maxAgents) {
		Wanderers w = get(world);
		long tick = world.getGameTime();
		Random random = new Random(world.getRandom().nextLong());
		if (w.nextArrival < 0) {
			w.nextArrival = tick + nextGap(random);
			w.setDirty();
			return;
		}
		if (tick < w.nextArrival) {
			return;
		}
		w.nextArrival = tick + nextGap(random);
		w.setDirty();
		PopulationRegistry registry = PopulationRegistry.get(world);
		long living = registry.population().allMinds().stream().filter(AgentMind::isAlive).count();
		int room = maxAgents > 0 ? (int) Math.max(0, maxAgents - living) : Integer.MAX_VALUE;
		int party = Math.min(room, partySize(random));
		if (party > 0) {
			arrive(world, registry, party, random, tick);
		}
	}

	private static void arrive(ServerLevel world, PopulationRegistry registry, int party, Random random, long tick) {
		List<BlockPos> settlers = new ArrayList<>();
		for (AgentMind mind : registry.population().allMinds()) {
			if (mind.isAlive() && world.getEntity(mind.identity().id()) instanceof AgentEntity body) {
				settlers.add(body.blockPosition());
			}
		}
		BlockPos anchor = settlers.isEmpty() ? world.getRespawnData().pos() : settlers.get(random.nextInt(settlers.size()));
		BlockPos at = null;
		for (int attempt = 0; attempt < 12 && at == null; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
			int x = anchor.getX() + (int) Math.round(Math.cos(angle) * distance);
			int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * distance);
			world.getChunk(x >> 4, z >> 4); // load (or generate) it so the ground is there
			BlockPos surface = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (world.getFluidState(surface.below()).isEmpty() && world.getFluidState(surface).isEmpty()) {
				at = surface;
			}
		}
		if (at == null) {
			return; // Only sea out there this time: nobody comes.
		}
		Set<String> taken = new HashSet<>();
		registry.population().allMinds().forEach(m -> taken.add(m.identity().name()));
		List<AgentMind> arrived = new ArrayList<>();
		for (int i = 0; i < party; i++) {
			UUID id = UUID.randomUUID();
			String name = AICivilizationMod.randomAgentName(random, taken);
			taken.add(name);
			AgentMind mind = registry.createMind(id, name, tick, random);
			AgentEntity body = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
			body.setUUID(id);
			double x = at.getX() + 0.5 + (i == 0 ? 0 : random.nextInt(5) - 2);
			double z = at.getZ() + 0.5 + (i == 0 ? 0 : random.nextInt(5) - 2);
			if (!AgentBodies.placeOnGround(world, body, x, at.getY(), z)) {
				body.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			}
			if (!world.addFreshEntity(body)) {
				registry.recordDeath(id);
				continue;
			}
			body.mind();
			registry.recordBodyChunk(id, body.chunkPosition().pack());
			arrived.add(mind);
		}
		EventLog log = EventLog.get(world);
		for (AgentMind mind : arrived) {
			List<AgentMind> companions = arrived.stream().filter(m -> m != mind).toList();
			for (AgentMind c : companions) {
				// They travelled together: they know and like each other a little.
				mind.relationships().with(c.identity().id()).recordConversation(tick, 0.3, 0.3);
			}
			String with = companions.isEmpty() ? "alone" : "with " + names(companions);
			mind.perceive(tick, "I came a long way to get here, " + with + ". This land is new to me.", 0.6,
					Set.copyOf(companions.stream().map(m -> m.identity().id()).toList()));
		}
		if (!arrived.isEmpty()) {
			log.append(tick, EventType.SPAWN, arrived.stream().map(m -> m.identity().id()).toList(),
					names(arrived) + (arrived.size() == 1 ? " wandered in from far away." : " wandered in together from far away."),
					List.of());
		}
	}

	private static String names(List<AgentMind> minds) {
		List<String> n = minds.stream().map(m -> m.identity().name()).toList();
		return n.size() == 1 ? n.get(0) : String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		nbt.putLong("nextArrival", nextArrival);
		return nbt;
	}

	private static Wanderers fromTag(CompoundTag nbt) {
		Wanderers w = new Wanderers();
		w.nextArrival = nbt.getLongOr("nextArrival", -1);
		return w;
	}
}
