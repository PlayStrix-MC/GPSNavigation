package com.gpsnavigation.model;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Логический дорожный маркер. Существует только в данных плагина (без блоков и сущностей).
 * Хранится по UUID, отображается коротким постоянным ID вида M-001.
 */
public final class RoadMarker {

    private final UUID uuid;
    private final int number;
    private final String mapId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private MarkerType type = MarkerType.ROAD;
    private final Set<UUID> neighbors = new LinkedHashSet<>();
    private final Map<String, Object> settings = new LinkedHashMap<>();

    public RoadMarker(UUID uuid, int number, String mapId, String world, int x, int y, int z) {
        this.uuid = uuid;
        this.number = number;
        this.mapId = mapId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static String formatId(int number) {
        return String.format("M-%03d", number);
    }

    public String shortId() { return formatId(number); }
    public UUID uuid() { return uuid; }
    public int number() { return number; }
    public String mapId() { return mapId; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public MarkerType type() { return type; }
    public void type(MarkerType value) { this.type = value; }
    public Set<UUID> neighbors() { return neighbors; }
    public Map<String, Object> settings() { return settings; }

    /** Центр блока по X/Z. */
    public double cx() { return x + 0.5; }
    /** Центр блока по Y. */
    public double cy() { return y + 0.5; }
    public double cz() { return z + 0.5; }

    public boolean isAt(String worldName, int bx, int by, int bz) {
        return x == bx && y == by && z == bz && world.equals(worldName);
    }

    public double horizontalDistance(RoadMarker other) {
        return Math.hypot(cx() - other.cx(), cz() - other.cz());
    }

    public double distance(RoadMarker other) {
        return Math.sqrt(distanceSq(other.cx(), other.cy(), other.cz()));
    }

    public double distanceSq(double px, double py, double pz) {
        double dx = cx() - px;
        double dy = cy() - py;
        double dz = cz() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Позиция "ног" на маркере (низ блока, центр по X/Z). */
    public Location location(World w) {
        return new Location(w, cx(), y, cz());
    }

    public String coords() {
        return x + ", " + y + ", " + z;
    }
}
