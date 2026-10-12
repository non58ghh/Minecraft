package com.aicivilization.action;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PhysicalActions {

    public static class ActionResult {
        public final boolean success;
        public final String message;

        public ActionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }

    /**
     * Dispatches action commands to their corresponding executors.
     */
    public ActionResult execute(Mob agent, JSONObject actionJson) {
        if (agent == null || !agent.isValid()) {
            return ActionResult.fail("Agent mob is invalid or not spawned.");
        }

        String action = actionJson.optString("action", "").toLowerCase();

        switch (action) {
            case "move_to":
                return handleMoveTo(agent, actionJson);
            case "flee":
                return handleFlee(agent, actionJson);
            case "mine_block":
                return handleMineBlock(agent, actionJson);
            case "place_block":
                return handlePlaceBlock(agent, actionJson);
            case "harvest_area":
            case "harvest_crop":
                return handleHarvestArea(agent, actionJson);
            case "chop_tree":
                return handleChopTree(agent, actionJson);
            case "attack":
                return handleAttack(agent, actionJson);
            case "sleep":
                return handleSleep(agent, actionJson);
            default:
                return ActionResult.fail("Unknown action: " + action);
        }
    }

    // --- Movement Handlers ---

    private ActionResult handleMoveTo(Mob agent, JSONObject json) {
        double x = json.optDouble("x", agent.getLocation().getX());
        double y = json.optDouble("y", agent.getLocation().getY());
        double z = json.optDouble("z", agent.getLocation().getZ());
        double speed = json.optDouble("speed", 1.0);

        Location target = new Location(agent.getWorld(), x, y, z);
        boolean pathFound = agent.getPathfinder().moveTo(target, speed);

        return pathFound
                ? ActionResult.ok("Navigating to (" + (int) x + ", " + (int) y + ", " + (int) z + ")")
                : ActionResult.fail("Cannot compute path to target location.");
    }

    private ActionResult handleFlee(Mob agent, JSONObject json) {
        double threatX = json.optDouble("threat_x");
        double threatZ = json.optDouble("threat_z");

        Location agentLoc = agent.getLocation();
        Vector awayDir = agentLoc.toVector().subtract(new Vector(threatX, agentLoc.getY(), threatZ)).normalize();

        // Check 3 candidate escape angles (direct away, +45 deg, -45 deg)
        Location bestTarget = null;
        double[] angles = {0, Math.PI / 4, -Math.PI / 4};

        for (double angle : angles) {
            Vector rotated = awayDir.clone().rotateAroundY(angle).multiply(16);
            Location candidate = agentLoc.clone().add(rotated);
            candidate.setY(agent.getWorld().getHighestBlockYAt(candidate) + 1);

            Block ground = candidate.clone().subtract(0, 1, 0).getBlock();
            if (ground.getType().isSolid() && !ground.isLiquid()) {
                bestTarget = candidate;
                break;
            }
        }

        if (bestTarget != null && agent.getPathfinder().moveTo(bestTarget, 1.25)) {
            return ActionResult.ok("Fleeing to safe position.");
        }

        return ActionResult.fail("No safe escape route found.");
    }

    // --- Gathering & Farming Handlers ---

    private ActionResult handleHarvestArea(Mob agent, JSONObject json) {
        int radius = Math.min(json.optInt("radius", 3), 5); // cap search radius
        Location center = agent.getLocation();
        int harvestedCount = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = center.clone().add(dx, dy, dz).getBlock();
                    if (block.getBlockData() instanceof Ageable) {
                        Ageable crop = (Ageable) block.getBlockData();
                        if (crop.getAge() == crop.getMaximumAge()) {
                            Material type = block.getType();
                            block.breakNaturally();
                            harvestedCount++;

                            // Auto-replant if seeds remain or setting enabled
                            if (json.optBoolean("replant", true)) {
                                block.setType(type);
                            }
                        }
                    }
                }
            }
        }

        if (harvestedCount > 0) {
            return ActionResult.ok("Harvested " + harvestedCount + " crops in area.");
        }
        return ActionResult.fail("No mature crops found in radius.");
    }

    private ActionResult handleChopTree(Mob agent, JSONObject json) {
        Block base = getTargetBlock(agent, json);
        if (base == null || !base.getType().name().endsWith("_LOG")) {
            return ActionResult.fail("Target block is not a log.");
        }

        int logsCut = 0;
        Block current = base;

        // Strip upward trunk sequentially up to 8 blocks
        while (current.getType().name().endsWith("_LOG") && logsCut < 8) {
            current.breakNaturally();
            logsCut++;
            current = current.getRelative(0, 1, 0);
        }

        return ActionResult.ok("Chopped tree: collected " + logsCut + " logs.");
    }

    private ActionResult handleMineBlock(Mob agent, JSONObject json) {
        Block target = getTargetBlock(agent, json);
        if (target == null || target.getType().isAir()) {
            return ActionResult.fail("Target block is air or unreachable.");
        }

        if (agent.getLocation().distance(target.getLocation()) > 5.0) {
            return ActionResult.fail("Target block is too far away to mine.");
        }

        target.breakNaturally();
        return ActionResult.ok("Mined " + target.getType().name());
    }

    private ActionResult handlePlaceBlock(Mob agent, JSONObject json) {
        Block target = getTargetBlock(agent, json);
        String matName = json.optString("material", "").toUpperCase();
        Material mat = Material.matchMaterial(matName);

        if (target == null || mat == null || !mat.isBlock()) {
            return ActionResult.fail("Invalid block placement location or material.");
        }

        target.setType(mat);
        return ActionResult.ok("Placed " + matName);
    }

    // --- Combat & Sleep Handlers ---

    private ActionResult handleAttack(Mob agent, JSONObject json) {
        double range = json.optDouble("range", 3.0);
        List<LivingEntity> nearby = new ArrayList<>();

        for (org.bukkit.entity.Entity e : agent.getNearbyEntities(range, range, range)) {
            if (e instanceof LivingEntity && !e.getUniqueId().equals(agent.getUniqueId())) {
                nearby.add((LivingEntity) e);
            }
        }

        if (nearby.isEmpty()) {
            return ActionResult.fail("No targets within attack range.");
        }

        LivingEntity target = nearby.get(0);
        agent.attack(target);
        return ActionResult.ok("Attacked " + target.getType().name());
    }

    private ActionResult handleSleep(Mob agent, JSONObject json) {
        Block bed = getTargetBlock(agent, json);
        if (bed == null || !bed.getType().name().endsWith("_BED")) {
            return ActionResult.fail("No valid bed at target location.");
        }

        long time = agent.getWorld().getTime();
        boolean isNight = time >= 12541 && time <= 23458;

        if (!isNight) {
            return ActionResult.fail("Cannot sleep during daylight.");
        }

        return ActionResult.ok("Entered sleep routine.");
    }

    // --- Utility Methods ---

    private Block getTargetBlock(Mob agent, JSONObject json) {
        if (!json.has("x") || !json.has("y") || !json.has("z")) {
            return null;
        }
        int x = json.getInt("x");
        int y = json.getInt("y");
        int z = json.getInt("z");
        return agent.getWorld().getBlockAt(x, y, z);
    }
}
