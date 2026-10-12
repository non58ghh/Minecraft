package com.aicivilization.mind;

import org.json.JSONObject;
import java.util.*;
import java.util.stream.Collectors;

public class MemoryStream {
    private final List<MemoryEntry> entries = new ArrayList<>();
    private final Map<String, JSONObject> knownLandmarks = new HashMap<>(); // e.g. "shelter", "bed", "communal_hub"

    public void add(MemoryEntry entry) {
        entries.add(entry);
    }

    /**
     * Stores or updates a known persistent location (home, shelter, bed).
     */
    public void recordLandmark(String name, double x, double y, double z) {
        JSONObject coords = new JSONObject();
        coords.put("name", name);
        coords.put("x", Math.round(x * 10.0) / 10.0);
        coords.put("y", Math.round(y * 10.0) / 10.0);
        coords.put("z", Math.round(z * 10.0) / 10.0);
        knownLandmarks.put(name.toLowerCase(), coords);
    }

    public JSONObject getLandmark(String name) {
        return knownLandmarks.get(name.toLowerCase());
    }

    /**
     * Retrieves the best known shelter coordinates for emergency dusk retreat.
     * Falls back to "bed", "shelter", or "communal_hub".
     */
    public JSONObject getBestShelterLocation() {
        if (knownLandmarks.containsKey("bed")) {
            return knownLandmarks.get("bed");
        }
        if (knownLandmarks.containsKey("shelter")) {
            return knownLandmarks.get("shelter");
        }
        if (knownLandmarks.containsKey("communal_hub")) {
            return knownLandmarks.get("communal_hub");
        }
        return null;
    }

    public List<String> getRecentSummaries(int count) {
        return entries.stream()
                .sorted(Comparator.comparingLong(MemoryEntry::getTimestamp).reversed())
                .limit(count)
                .map(MemoryEntry::getDescription)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }
}
