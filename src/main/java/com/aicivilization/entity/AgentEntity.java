package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.behavior.NeedsDrivenGoal;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.perception.Embodied;
import com.aicivilization.population.PopulationRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;

/**
 * The physical embodiment of one {@link AgentMind} in the Minecraft world.
 * Deliberately thin: it looks up (or creates) its mind by its own entity
 * UUID, runs perception against itself, asks the mind what it wants, and
 * turns that into concrete Minecraft actions. The mind itself has no idea
 * this class exists.
 */
public final class AgentEntity extends PathAwareEntity implements Embodied {

	private AgentMind mind;

	public AgentEntity(EntityType<? extends AgentEntity> type, World world) {
		super(type, world);
		this.goalSelector.add(1, new NeedsDrivenGoal(this));
		this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(9, new LookAroundGoal(this));
	}

	public static DefaultAttributeContainer.Builder createAgentAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0);
	}

	/**
	 * Looks up (or, on first tick after spawning, creates) this entity's
	 * mind in the world's {@link PopulationRegistry}, keyed by this entity's
	 * own UUID. Minecraft already persists entity UUIDs across save/reload,
	 * so this is all the linkage needed for the mind to survive a restart.
	 */
	public AgentMind mind() {
		if (mind == null && getWorld() instanceof ServerWorld serverWorld) {
			PopulationRegistry registry = PopulationRegistry.get(serverWorld);
			mind = registry.population().getMind(getUuid()).orElseGet(() -> {
				java.util.Random rng = new java.util.Random(random.nextLong());
				String name = AICivilizationMod.randomAgentName(rng);
				AgentMind created = registry.createMind(getUuid(), name, serverWorld.getTime(), rng);
				setCustomName(Text.literal(name));
				setCustomNameVisible(true);
				EventLog.get(serverWorld).append(serverWorld.getTime(), EventType.SPAWN,
						List.of(getUuid()), name + " came into being.", List.of());
				return created;
			});
			if (mind.identity().name() != null && getCustomName() == null) {
				setCustomName(Text.literal(mind.identity().name()));
				setCustomNameVisible(true);
			}
		}
		return mind;
	}

	@Override
	public void tick() {
		super.tick();
		if (!getWorld().isClient()) {
			// Ensures the mind exists even before NeedsDrivenGoal's first run.
			mind();
		}
	}

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		if (getWorld() instanceof ServerWorld serverWorld && mind != null) {
			PopulationRegistry.get(serverWorld).recordDeath(getUuid());
			EventLog.get(serverWorld).append(serverWorld.getTime(), EventType.DEATH,
					List.of(getUuid()),
					mind.identity().name() + " has died.",
					List.of(Cause.needState("safety", mind.needs().safety())));
		}
	}

	@Override
	public UUID agentId() {
		return getUuid();
	}

	@Override
	public String agentDisplayName() {
		AgentMind m = mind();
		return m != null ? m.identity().name() : "Someone";
	}
}
