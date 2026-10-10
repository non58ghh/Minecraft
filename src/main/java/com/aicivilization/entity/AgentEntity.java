package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.behavior.NeedsDrivenGoal;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.perception.Embodied;
import com.aicivilization.population.PopulationRegistry;
import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * The physical embodiment of one {@link AgentMind} in the Minecraft world.
 * Deliberately thin: it looks up (or creates) its mind by its own entity
 * UUID, runs perception against itself, asks the mind what it wants, and
 * turns that into concrete Minecraft actions. The mind itself has no idea
 * this class exists.
 */
public final class AgentEntity extends PathfinderMob implements Embodied, PolymerEntity {

	private AgentMind mind;
	private final NeedsDrivenGoal behavior;

	public AgentEntity(EntityType<? extends AgentEntity> type, Level world) {
		super(type, world);
		// Swim up in water instead of sinking and drowning, as every vanilla land mob does.
		this.goalSelector.addGoal(0, new FloatGoal(this));
		// ...but don't plan routes that swim across open water: agents drifted out to sea that way.
		this.getNavigation().setCanFloat(false);
		// Never walk into water at all: off a bank into the sea there's often no climbing back out.
		this.setPathfindingMalus(PathType.WATER, -1.0f);
		// Nor onto powder snow: an agent sank into it on a mountain and froze to death.
		this.setPathfindingMalus(PathType.POWDER_SNOW, -1.0f);
		this.setPathfindingMalus(PathType.ON_TOP_OF_POWDER_SNOW, -1.0f);
		this.behavior = new NeedsDrivenGoal(this);
		this.goalSelector.addGoal(1, behavior);
		// The tool in hand is only a display of what's in its pack: never drop it as a second copy.
		this.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0f);
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	/**
	 * What clients without this mod are told this entity is. Vanilla Java
	 * clients and Bedrock players (through Geyser) can't decode a modded entity
	 * type, so Polymer sends a player instead, introduced by
	 * {@link #onBeforeSpawnPacket}. Only what clients are told changes; the
	 * server-side entity and its mind are untouched. Data the player type
	 * doesn't have (the mob flags) is dropped by Polymer.
	 */
	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		return EntityTypes.PLAYER;
	}

	/**
	 * A player entity only renders once the client has a player-info entry for
	 * its UUID, so send one just before the spawn packet. It's unlisted, so
	 * agents stay out of the tab list. With no skin attached, clients pick a
	 * default skin from the UUID, so agents look varied but stable.
	 */
	@Override
	public void onBeforeSpawnPacket(ServerPlayer player, Consumer<Packet<?>> packetConsumer) {
		ClientboundPlayerInfoUpdatePacket info = PolymerEntityUtils.createMutablePlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER));
		info.entries().add(new ClientboundPlayerInfoUpdatePacket.Entry(getUUID(),
				new GameProfile(getUUID(), PlayerDisguise.profileName(displayName())),
				false, 0, GameType.SURVIVAL, null, true, 0, null));
		packetConsumer.accept(info);
	}

	/** Drops the player-info entry once the agent is gone for good (died or discarded), not on chunk unload. */
	@Override
	public void remove(RemovalReason reason) {
		super.remove(reason);
		if (reason.shouldDestroy() && level() instanceof ServerLevel serverWorld) {
			serverWorld.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));
		}
	}

	private String displayName() {
		if (getCustomName() != null) {
			return getCustomName().getString();
		}
		AgentMind mind = mind();
		return mind == null ? null : mind.identity().name();
	}

	public static AttributeSupplier.Builder createAgentAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.FOLLOW_RANGE, 48.0)
				.add(Attributes.ATTACK_DAMAGE, 3.0);
	}

	/**
	 * Looks up (or, on first tick after spawning, creates) this entity's
	 * mind in the world's {@link PopulationRegistry}, keyed by this entity's
	 * own UUID. Minecraft already persists entity UUIDs across save/reload,
	 * so this is all the linkage needed for the mind to survive a restart.
	 */
	public AgentMind mind() {
		if (mind == null && level() instanceof ServerLevel serverWorld) {
			PopulationRegistry registry = PopulationRegistry.get(serverWorld);
			mind = registry.population().getMind(getUUID()).orElseGet(() -> {
				java.util.Random rng = new java.util.Random(random.nextLong());
				java.util.Set<String> taken = new java.util.HashSet<>();
				registry.population().allMinds().forEach(m -> taken.add(m.identity().name()));
				String name = AICivilizationMod.randomAgentName(rng, taken);
				AgentMind created = registry.createMind(getUUID(), name, serverWorld.getGameTime(), rng);
				setCustomName(Component.literal(name));
				setCustomNameVisible(true);
				EventLog.get(serverWorld).append(serverWorld.getGameTime(), EventType.SPAWN,
						List.of(getUUID()), name + " came into being.", List.of());
				return created;
			});
			if (mind.identity().name() != null && getCustomName() == null) {
				setCustomName(Component.literal(mind.identity().name()));
				setCustomNameVisible(true);
			}
		}
		return mind;
	}

	/**
	 * Agents are permanent residents, never ambient mobs. Without this,
	 * vanilla deletes a mob that is more than 128 blocks from a player (and
	 * randomly past 32), which removes the body without a death, leaving its
	 * mind "alive" with nothing to act through.
	 */
	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide()) {
			// Ensures the mind exists even before NeedsDrivenGoal's first run.
			mind();
			if (tickCount % GROW_CHECK_TICKS == 0) {
				growUp(level().getGameTime());
			}
		}
	}

	private static final int GROW_CHECK_TICKS = 200;
	/** A newborn is this big (and this sturdy) beside a grown agent. */
	private static final double NEWBORN_SIZE = 0.5;

	/**
	 * A child is small and frail, and fills out as it grows; the day it's
	 * grown is a milestone. Grown agents are left as they are.
	 */
	public void growUp(long tick) {
		AgentMind m = mind();
		if (m == null || m.parents().isEmpty() || !(level() instanceof ServerLevel serverWorld)) {
			return;
		}
		var scale = getAttribute(Attributes.SCALE);
		var health = getAttribute(Attributes.MAX_HEALTH);
		if (scale == null || health == null) {
			return;
		}
		boolean wasChild = scale.getBaseValue() < 1.0;
		double growth = m.growth(tick);
		double size = NEWBORN_SIZE + (1.0 - NEWBORN_SIZE) * growth;
		if (Math.abs(scale.getBaseValue() - size) > 0.01 || growth >= 1.0) {
			scale.setBaseValue(size);
			health.setBaseValue(20.0 * size);
			if (getHealth() > getMaxHealth()) {
				setHealth(getMaxHealth());
			}
		}
		if (wasChild && growth >= 1.0 && m.isAlive()) {
			m.perceive(tick, "I'm grown now, and can make my own way.", 0.7, java.util.Set.of());
			EventLog.get(serverWorld).append(tick, EventType.MILESTONE, List.of(getUUID()),
					m.identity().name() + " has grown up.", List.of());
		}
	}

	/** A monster's blow (or its arrow) is felt by the mind, not just the body. */
	@Override
	public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
		boolean hurt = super.hurtServer(world, source, amount);
		if (hurt && isAlive() && source.getEntity() instanceof Monster monster) {
			behavior.onHurtByMonster(world, monster);
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel serverWorld && mind != null) {
			PopulationRegistry.get(serverWorld).recordDeath(getUUID());
			String how = source.is(DamageTypes.STARVE) ? "starved to death"
					: source.getEntity() instanceof Monster monster ? "was killed by a " + NeedsDrivenGoal.monsterName(monster)
					: source.is(DamageTypes.DROWN) ? "drowned"
					: source.is(DamageTypes.FALL) ? "died from a fall"
					: "died";
			EventLog.get(serverWorld).append(serverWorld.getGameTime(), EventType.DEATH,
					List.of(getUUID()),
					mind.identity().name() + " " + how + ".",
					List.of(source.is(DamageTypes.STARVE)
							? Cause.needState("food", mind.needs().food())
							: Cause.needState("safety", mind.needs().safety())));
			com.aicivilization.behavior.Mourning.onDeath(this, mind, how, serverWorld, serverWorld.getGameTime());
		}
	}

	@Override
	public UUID agentId() {
		return getUUID();
	}

	@Override
	public String agentDisplayName() {
		AgentMind m = mind();
		return m != null ? m.identity().name() : "Someone";
	}
}
