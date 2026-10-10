package com.aicivilization.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

/**
 * Makes the monsters that hunt players hunt agents too: zombies (husks and
 * drowned among them), skeletons, spiders and creepers. Vanilla gives each
 * monster a fixed list of what it goes after, and an agent, not being a
 * player or a villager, isn't on any of them.
 *
 * <p>The goal is added to the monster's ordinary goal list (the target list
 * isn't reachable from outside), and only looks for an agent while the
 * monster has no target of its own, so a monster already after a player
 * keeps after them. Like the vanilla player hunt it needs a line of sight,
 * and spiders only hunt in the dark, as they do players.
 */
public final class MonsterHunting {

	private MonsterHunting() {
	}

	/** On every entity load: gives a hunting monster its agent hunt, once. */
	public static void onLoad(Entity entity, ServerLevel world) {
		if (!(entity instanceof Mob mob) || !hunts(mob)) {
			return;
		}
		for (WrappedGoal wrapped : mob.getGoalSelector().getAvailableGoals()) {
			if (wrapped.getGoal() instanceof HuntAgentsGoal) {
				return;
			}
		}
		mob.getGoalSelector().addGoal(2, new HuntAgentsGoal(mob));
	}

	/** The monsters that go after players on sight. Zombified piglins are neutral and left alone. */
	static boolean hunts(Mob mob) {
		return mob instanceof Zombie && !(mob instanceof ZombifiedPiglin)
				|| mob instanceof AbstractSkeleton
				|| mob instanceof Spider
				|| mob instanceof Creeper;
	}

	/** Goes after an agent it can see, while it has nothing else to go after. */
	static final class HuntAgentsGoal extends NearestAttackableTargetGoal<AgentEntity> {
		HuntAgentsGoal(Mob mob) {
			super(mob, AgentEntity.class, true, (LivingEntity target, ServerLevel level) -> mob.getTarget() == null
					&& (!(mob instanceof Spider) || mob.getLightLevelDependentMagicValue() < 0.5f));
		}
	}
}
