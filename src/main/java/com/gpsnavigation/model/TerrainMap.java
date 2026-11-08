package com.gpsnavigation.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** Карта местности: собственная дорожная сеть, NPC и мёртвые зоны в одном мире. */
public final class TerrainMap {

    private final String id;
    private final String world;
    private String displayName;
    private List<String> description = new ArrayList<>();
    private Material icon = Material.FILLED_MAP;

    public TerrainMap(String id, String world, String displayName) {
        this.id = id;
        this.world = world;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String world() { return world; }
    public String displayName() { return displayName; }
    public void displayName(String value) { this.displayName = value; }
    public List<String> description() { return description; }
    public void description(List<String> value) { this.description = new ArrayList<>(value); }
    public Material icon() { return icon; }
    public void icon(Material value) { this.icon = value; }
}
