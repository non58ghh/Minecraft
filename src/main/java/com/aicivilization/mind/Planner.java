package com.aicivilization.mind;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Planner {

    /**
     * Builds the complete contextual prompt sent to the LLM.
     */
    public String buildPrompt(JSONObject perception, String characterProfile, String currentGoal, List<String> recentMemories) {
        StringBuilder prompt = new StringBuilder();

        // 1. Identity & Persona
        prompt.append("You are an autonomous resident in a survival Minecraft civilization.\n");
        prompt.append("Character Profile:\n").append(characterProfile).append("\n\n");

        // 2. Environmental & Survival State
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

        // 3. Survival Alerts & Directives
        if (threats != null && threats.length() > 0) {
            prompt.append("\n⚠️ THREATS DETECTED: ").append(threats.length()).append(" hostile mob(s) nearby!\n");
            for (int i = 0; i < threats.length(); i++) {
                JSONObject t = threats.getJSONObject(i);
                prompt.append(" - ").append(t.optString("type")).append(" at distance ").append(t.optDouble("distance")).append(" blocks\n");
            }
        }

        if (timeInfo != null) {
            String urgency = timeInfo.optString("survival_urgency", "NORMAL");
            if (!urgency.equals("NORMAL")) {
                prompt.append("\n🚨 SURVIVAL DIRECTIVE: ").append(urgency).append("\n");
                prompt.append("PRIORITY RULE: Do NOT start or continue outdoor harvesting, tree chopping, or mining.\n");
                prompt.append("Proceed to shelter immediately or enter lockdown.\n");
            }
        }

        // 4. Intentions & Memory Stream Context
        prompt.append("\n=== Current Intention ===\n");
        prompt.append("Active Goal: ").append(currentGoal).append("\n");

        if (recentMemories != null && !recentMemories.isEmpty()) {
            prompt.append("\nRecent Memories & Observations:\n");
            for (String mem : recentMemories) {
                prompt.append("- ").append(mem).append("\n");
            }
        }

        // 5. Action Schema & Output Constraints
        prompt.append("\n=== Instructions & Available Actions ===\n");
        prompt.append("Respond ONLY with a valid JSON array of action objects.\n");
        prompt.append("Supported actions:\n");
        prompt.append("- {\"action\": \"move_to\", \"x\": <num>, \"y\": <num>, \"z\": <num>, \"speed\": <opt_num>}\n");
        prompt.append("- {\"action\": \"flee\", \"threat_x\": <num>, \"threat_z\": <num>}\n");
        prompt.append("- {\"action\": \"harvest_area\", \"radius\": 3, \"replant\": true}\n");
        prompt.append("- {\"action\": \"chop_tree\", \"x\": <num>, \"y\": <num>, \"z\": <num>}\n");
        prompt.append("- {\"action\": \"mine_block\", \"x\": <num>, \"y\": <num>, \"z\": <num>}\n");
        prompt.append("- {\"action\": \"place_block\", \"x\": <num>, \"y\": <num>, \"z\": <num>, \"material\": \"<name>\"}\n");
        prompt.append("- {\"action\": \"sleep\", \"x\": <num>, \"y\": <num>, \"z\": <num>}\n");
        prompt.append("- {\"action\": \"attack\", \"range\": 3.0}\n\n");
        prompt.append("Example Output:\n");
        prompt.append("[{\"action\": \"move_to\", \"x\": 104.5, \"y\": 65.0, \"z\": -12.3}]\n");

        return prompt.toString();
    }

    /**
     * Determines whether standard planning should be bypassed for an immediate retreat.
     * Prevents LLM lag or goal persistence from leaving the agent outside after dark.
     */
    public JSONArray getEmergencyOverridePlan(JSONObject perception, JSONObject shelterCoords) {
        if (perception == null || shelterCoords == null) {
            return null;
        }

        JSONObject timeInfo = perception.optJSONObject("time_info");
        JSONObject self = perception.optJSONObject("self");

        if (timeInfo == null || self == null) {
            return null;
        }

        boolean isSheltered = self.optBoolean("is_sheltered", false);
        boolean isNight = timeInfo.optBoolean("is_night", false);
        String phase = timeInfo.optString("phase", "");

        // If it is dusk alert or active night and the agent is unsheltered, route straight to shelter
        if ((phase.equals("dusk_alert") || isNight) && !isSheltered) {
            JSONArray plan = new JSONArray();
            JSONObject returnAction = new JSONObject();
            returnAction.put("action", "move_to");
            returnAction.put("x", shelterCoords.optDouble("x"));
            returnAction.put("y", shelterCoords.optDouble("y"));
            returnAction.put("z", shelterCoords.optDouble("z"));
            returnAction.put("speed", 1.25);
            plan.put(returnAction);
            return plan;
        }

        return null;
    }

    /**
     * Parses the LLM's raw response string into executable JSON action steps.
     */
    public List<JSONObject> parsePlan(String llmResponse) {
        List<JSONObject> actionList = new ArrayList<>();
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return actionList;
        }

        try {
            String cleaned = llmResponse.trim();
            // Strip markdown JSON code fence formatting if present
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            JSONArray array = new JSONArray(cleaned);
            for (int i = 0; i < array.length(); i++) {
                actionList.add(array.getJSONObject(i));
            }
        } catch (Exception e) {
            System.err.println("[Planner] Failed to parse action JSON: " + e.getMessage());
        }

        return actionList;
    }
}
