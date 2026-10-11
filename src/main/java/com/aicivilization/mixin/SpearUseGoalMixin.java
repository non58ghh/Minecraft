package com.aicivilization.mixin;

import net.minecraft.world.entity.ai.goal.SpearUseGoal;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's spear attack checks for a target when deciding whether to keep
 * going but not in its tick, so a spear-carrying monster whose target
 * vanished in between (an agent it was chasing died, or its target goal let
 * go) crashes the whole server. With no target there's nothing to do this
 * tick; the goal stops at its next check, as vanilla intends.
 */
@Mixin(SpearUseGoal.class)
public abstract class SpearUseGoalMixin {

	@Shadow
	@Final
	private Monster mob;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void aicivilization$noTargetNoThrust(CallbackInfo ci) {
		if (mob.getTarget() == null) {
			ci.cancel();
		}
	}
}
