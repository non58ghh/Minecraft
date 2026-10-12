package com.aicivilization.action;

import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class PhysicalActions {

    public static ActionResult handleSleep(MobEntity agent, JsonObject json) {
        BlockPos bedPos = getTargetBlockPos(agent, json);
        World world = agent.getWorld();
        
        if (world.getBlockState(bedPos).isOf(Blocks.RED_BED) || world.getBlockState(bedPos).getBlock().getTranslationKey().contains("bed")) {
            agent.sleep(bedPos);
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }

    public static BlockPos getTargetBlockPos(MobEntity agent, JsonObject json) {
        if (json.has("x") && json.has("y") && json.has("z")) {
            int x = json.get("x").getAsInt();
            int y = json.get("y").getAsInt();
            int z = json.get("z").getAsInt();
            return new BlockPos(x, y, z);
        }
        return agent.getBlockPos();
    }

    public static ActionResult handleHarvest(MobEntity agent, JsonObject json) {
        BlockPos pos = getTargetBlockPos(agent, json);
        World world = agent.getWorld();
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof CropBlock crop) {
            if (crop.isMature(state)) {
                world.breakBlock(pos, true, agent);
                world.setBlockState(pos, crop.withAge(0));
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.PASS;
    }

    public static ActionResult handleFlee(MobEntity agent, LivingEntity threat) {
        Vec3d threatPos = threat.getPos();
        Vec3d agentPos = agent.getPos();
        Vec3d fleeDir = agentPos.subtract(threatPos).normalize().multiply(10.0);
        
        BlockPos fleeTarget = BlockPos.ofFloored(agentPos.add(fleeDir));
        agent.getNavigation().startMovingTo(fleeTarget.getX(), fleeTarget.getY(), fleeTarget.getZ(), 1.25);
        return ActionResult.SUCCESS;
    }
}
