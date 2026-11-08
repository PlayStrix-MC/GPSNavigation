package com.gpsnavigation.model;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Точка назначения GPS (NPC). Привязывается к ближайшему дорожному маркеру своей карты. */
public final class GpsNpc {

    private final String id;
    private String mapId;
    private String world;
    private double x;
    private double y;
    private double z;
    private String displayName;
    private List<String> description = new ArrayList<>();
    private Material icon = Material.PLAYER_HEAD;
    private UUID linkedMarker;
    /** Сущность NPC в мире (для свечения), задаётся /gpsnpc bind. */
    private UUID entityUuid;

    public GpsNpc(String id, String mapId, String world, double x, double y, double z, String displayName) {
        this.id = id;
        this.mapId = mapId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String mapId() { return mapId; }
    public void mapId(String value) { this.mapId = value; }
    public String world() { return world; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public String displayName() { return displayName; }
    public void displayName(String value) { this.displayName = value; }
    public List<String> description() { return description; }
    public void description(List<String> value) { this.description = new ArrayList<>(value); }
    public Material icon() { return icon; }
    public void icon(Material value) { this.icon = value; }
    public UUID linkedMarker() { return linkedMarker; }
    public void linkedMarker(UUID value) { this.linkedMarker = value; }

    public UUID entityUuid() { return entityUuid; }
    public void entityUuid(UUID value) { this.entityUuid = value; }

    public void moveTo(String worldName, double nx, double ny, double nz) {
        this.world = worldName;
        this.x = nx;
        this.y = ny;
        this.z = nz;
    }

    public Location location(World w) {
        return new Location(w, x, y, z);
    }

    public String coords() {
        return (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
    }
}
