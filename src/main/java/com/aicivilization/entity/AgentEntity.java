package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.behavior.NeedsDrivenGoal;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.perception.Embodied;
import com.aicivilization.population.PopulationRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The physical embodiment of one {@link AgentMind} in the Minecraft world.
 * Deliberately thin: it looks up (or creates) its mind by its own entity
 * UUID, runs perception against itself, asks the mind what it wants, and
 * turns that into concrete Minecraft actions. The mind itself has no idea
 * this class exists.
 */
public final class AgentEntity extends PathfinderMob implements Embodied {

	private AgentMind mind;

	public AgentEntity(EntityType<? extends AgentEntity> type, Level world) {
		super(type, world);
		this.goalSelector.addGoal(1, new NeedsDrivenGoal(this));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	public static AttributeSupplier.Builder createAgentAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.FOLLOW_RANGE, 24.0)
				.add(Attributes.ATTACK_DAMAGE, 1.0);
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
				String name = AICivilizationMod.randomAgentName(rng);
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
					mind.identity().name() + " has died.",
					List.of(Cause.needState("safety", mind.needs().safety())));
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
