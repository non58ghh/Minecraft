package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.behavior.NeedsDrivenGoal;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.perception.Embodied;
import com.aicivilization.population.PopulationRegistry;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.player.Player;
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
		this.goalSelector.addGoal(1, new NeedsDrivenGoal(this));
		// The tool in hand is only a display of what's in its pack: never drop it as a second copy.
		this.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0f);
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	/**
	 * What clients without this mod are told this entity is. Vanilla Java
	 * clients and Bedrock players (through Geyser) can't decode a modded entity
	 * type, so Polymer sends a villager instead; the agent's name tag still
	 * shows. Only what clients are told changes; the server-side entity and its
	 * mind are untouched.
	 */
	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		return EntityTypes.VILLAGER;
	}

	public static AttributeSupplier.Builder createAgentAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.FOLLOW_RANGE, 24.0)
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
		}
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel serverWorld && mind != null) {
			PopulationRegistry.get(serverWorld).recordDeath(getUUID());
			EventLog.get(serverWorld).append(serverWorld.getGameTime(), EventType.DEATH,
					List.of(getUUID()),
					mind.identity().name() + (source.is(DamageTypes.STARVE) ? " starved to death." : " has died."),
					List.of(source.is(DamageTypes.STARVE)
							? Cause.needState("food", mind.needs().food())
							: Cause.needState("safety", mind.needs().safety())));
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
