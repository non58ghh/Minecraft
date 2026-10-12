package com.aicivilization.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class Planner {

    public static JsonArray evaluatePerception(JsonObject perception) {
        JsonArray plan = new JsonArray();

        // Check for threats first
        if (perception.has("threats")) {
            JsonArray threats = perception.getAsJsonArray("threats");
            for (JsonElement elem : threats) {
                if (elem.isJsonObject()) {
                    JsonObject threat = elem.getAsJsonObject();
                    if (threat.has("is_hostile") && threat.get("is_hostile").getAsBoolean()) {
                        JsonObject fleeAction = new JsonObject();
                        fleeAction.addProperty("action", "flee");
                        fleeAction.add("target", threat);
                        plan.add(fleeAction);
                        return plan; // Immediate emergency response
                    }
                }
            }
        }

        // Check time and safety
        if (perception.has("time_info")) {
            JsonObject timeInfo = perception.getAsJsonObject("time_info");
            boolean isNight = timeInfo.has("is_night") && timeInfo.get("is_night").getAsBoolean();
            
            if (isNight) {
                JsonObject sleepAction = new JsonObject();
                sleepAction.addProperty("action", "sleep");
                plan.add(sleepAction);
                return plan;
            }
        }

        // Default idle or return action
        JsonObject defaultAction = new JsonObject();
        defaultAction.addProperty("action", "wander");
        plan.add(defaultAction);

        return plan;
    }
}
