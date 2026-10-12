package com.aicivilization.mind;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Planner {

    /**
     * Builds the system & contextual prompt sent to the LLM.
     */
    public String buildPrompt(JSONObject perception, String characterProfile, String currentGoal, List<String> recentMemories) {
        StringBuilder prompt = new StringBuilder();

        // 1. Identity & Profile
        prompt.append("You are an autonomous resident in a survival Minecraft civilization.\n");
        prompt.append("Character Profile:\n").append(characterProfile).append("\n\n");

        // 2. Environmental State
        JSONObject timeInfo = perception.optJSONObject("time_info");
        JSONObject self = perception.optJSONObject("self");
        JSONArray threats = perception.optJSONArray("nearby_threats");

        prompt.append("=== Current Environment ===\n");
        if (timeInfo != null) {
            prompt.append("Time: Day ").append(timeInfo.optInt("day"))
                  .append(" (").append(timeInfo.optString("time", "12:00")).append("), Phase: ")
                  .append(timeInfo.optString("phase", "day")).append("\n");
        }

        if (self != null) {
            prompt.append("Health: ").append(self.optDouble("health")).append("/").append(self.optDouble("max_health")).append("\n");
            prompt.append("Position: (").append(self.optDouble("x")).append(", ")
                  .append(self.optDouble("y")).append(", ").append(self.optDouble("z")).append(")\n");
            prompt.append("Sheltered: ").append(self.optBoolean("is_sheltered", false)).append("\n");
            prompt.append("Light Level: ").append(self.optInt("block_light", 0)).append("\n");
        }

        // 3. Survival Directives & Hostile Threats
        if (threats != null && threats.length() > 0) {
            prompt.append("\n⚠️ THREATS DETECTED: ").append(threats.length()).append(" hostile mob(s) nearby!\n");
            for (int i = 0; i < threats.length(); i++) {
                JSONObject t = threats.getJSONObject(i);
                prompt.append(" - ").append(t.optString("type")).append(" at distance ").append(t.optDouble("distance")).append("\n");
            }
        }

        if (timeInfo != null) {
            String urgency = timeInfo.optString("survival_urgency", "NORMAL");
            if (!urgency.equals("NORMAL")) {
                prompt.append("\n🚨 ").append(urgency).append("\n");
                prompt.append("RULE: Do NOT begin or continue outdoor gathering, logging, or exploratory travel. Move to an enclosed, lit shelter immediately.\n");
            }
        }

        // 4. Memory & Intentions
        prompt.append("\n=== Current Intention ===\n");
        prompt.append("Active Goal: ").append(currentGoal).append("\n");

        if (recentMemories != null && !recentMemories.isEmpty()) {
            prompt.append("Recent Events:\n");
            for (String mem : recentMemories) {
                prompt.append("- ").append(mem).append("\n");
            }
        }

        // 5. Output Instructions
        prompt.append("\n=== Instructions ===\n");
        prompt.append("Respond with a valid JSON array of action objects to execute next.\n");
        prompt.append("Valid actions: move_to, flee, harvest_area, chop_tree, mine_block, place_block, sleep, attack.\n");
        prompt.append("Example output: [{\"action\": \"move_to\", \"x\": 120, \"y\": 64, \"z\": -45}]\n");

        return prompt.toString();
    }

    /**
     * Checks if an immediate survival override plan is required without waiting on complex goals.
     */
    public JSONArray getEmergencyOverridePlan(JSONObject perception, JSONObject homeCoords) {
        JSONObject timeInfo = perception.optJSONObject("time_info");
        JSONObject self = perception.optJSONObject("self");

        if (timeInfo == null || self == null) return null;

        boolean isSheltered = self.optBoolean("is_sheltered", false);
        boolean isNight = timeInfo.optBoolean("is_night", false);
        String phase = timeInfo.optString("phase", "");

        // If it's dusk alert or night and the bot is outdoors without cover, route home immediately
        if ((phase.equals("dusk_alert") || isNight) && !isSheltered && homeCoords != null) {
            JSONArray emergencyPlan = new JSONArray();
            JSONObject returnHome = new JSONObject();
            returnHome.put("action", "move_to");
            returnHome.put("x", homeCoords.optDouble("x"));
            returnHome.put("y", homeCoords.optDouble("y"));
            returnHome.put("z", homeCoords.optDouble("z"));
            returnHome.put("speed", 1.2);
            emergencyPlan.put(returnHome);
            return emergencyPlan;
        }

        return null;
    }
}
