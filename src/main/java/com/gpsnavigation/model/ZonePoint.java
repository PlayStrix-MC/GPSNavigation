package com.gpsnavigation.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Точка мёртвой зоны. Работает как дорожный маркер: соседние точки соединяются автоматически,
 * а площадь, замкнутая соединениями, становится мёртвой зоной. Существует только в данных плагина.
 */
public final class ZonePoint {

    private final UUID uuid;
    private final int number;
    private final String mapId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final Set<UUID> neighbors = new LinkedHashSet<>();

    public ZonePoint(UUID uuid, int number, String mapId, String world, int x, int y, int z) {
        this.uuid = uuid;
        this.number = number;
        this.mapId = mapId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static String formatId(int number) {
        return String.format("Z-%03d", number);
    }

    public String shortId() { return formatId(number); }
    public UUID uuid() { return uuid; }
    public int number() { return number; }
    public String mapId() { return mapId; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public Set<UUID> neighbors() { return neighbors; }

    public double cx() { return x + 0.5; }
    public double cy() { return y + 0.5; }
    public double cz() { return z + 0.5; }

    public boolean isAt(String worldName, int bx, int by, int bz) {
        return x == bx && y == by && z == bz && world.equals(worldName);
    }

    public double distanceSq(double px, double py, double pz) {
        double dx = cx() - px;
        double dy = cy() - py;
        double dz = cz() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(ZonePoint o) {
        return Math.sqrt(distanceSq(o.cx(), o.cy(), o.cz()));
    }

    public String coords() {
        return x + ", " + y + ", " + z;
    }
}
