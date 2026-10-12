package com.aicivilization.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;

public class MemoryStream {

    private final JsonArray memoryLog = new JsonArray();

    public void recordLandmark(String name, BlockPos pos) {
        JsonObject memory = new JsonObject();
        memory.addProperty("type", "landmark");
        memory.addProperty("name", name);

        JsonObject coords = new JsonObject();
        coords.addProperty("x", pos.getX());
        coords.addProperty("y", pos.getY());
        coords.addProperty("z", pos.getZ());
        memory.add("coords", coords);

        memoryLog.add(memory);
    }

    public void recordEvent(String description) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "event");
        event.addProperty("description", description);
        event.addProperty("timestamp", System.currentTimeMillis());
        memoryLog.add(event);
    }

    public JsonArray getMemories() {
        return memoryLog;
    }
}
