package com.aicivilization.mind;

public class MemoryEntry {
    private final String id;
    private final String description;
    private final MemoryType type;
    private final int importance; // 1-10
    private final long timestamp; // world game tick
    private final Double x;
    private final Double y;
    private final Double z;

    public enum MemoryType {
        OBSERVATION,
        ACTION,
        REFLECTION,
        DIALOGUE,
        LANDMARK
    }

    public MemoryEntry(String id, String description, MemoryType type, int importance, long timestamp) {
        this(id, description, type, importance, timestamp, null, null, null);
    }

    public MemoryEntry(String id, String description, MemoryType type, int importance, long timestamp, Double x, Double y, Double z) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.importance = importance;
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public MemoryType getType() { return type; }
    public int getImportance() { return importance; }
    public long getTimestamp() { return timestamp; }
    public Double getX() { return x; }
    public Double getY() { return y; }
    public Double getZ() { return z; }
    public boolean hasLocation() { return x != null && y != null && z != null; }
}
