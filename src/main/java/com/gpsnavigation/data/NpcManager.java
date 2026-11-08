package com.gpsnavigation.data;

import com.gpsnavigation.model.GpsNpc;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** NPC навигации (npcs.jmc). */
public final class NpcManager extends DataStore {

    private final Map<String, GpsNpc> npcs = new LinkedHashMap<>();

    public NpcManager(File dataFolder) {
        super(dataFolder, "npcs", "GPSNavigation — NPC-цели маршрутов. Файл ведёт плагин; правьте при выключенном сервере или делайте /gps reload.");
    }

    public static String normalize(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    public GpsNpc get(String id) {
        return npcs.get(normalize(id));
    }

    public Collection<GpsNpc> all() {
        return npcs.values();
    }

    public List<GpsNpc> ofMap(String mapId) {
        List<GpsNpc> list = new ArrayList<>();
        for (GpsNpc n : npcs.values()) {
            if (n.mapId().equals(mapId)) list.add(n);
        }
        return list;
    }

    public List<GpsNpc> linkedTo(UUID marker) {
        List<GpsNpc> list = new ArrayList<>();
        for (GpsNpc n : npcs.values()) {
            if (marker.equals(n.linkedMarker())) list.add(n);
        }
        return list;
    }

    public GpsNpc create(String id, String mapId, String world, double x, double y, double z, String name) {
        GpsNpc npc = new GpsNpc(normalize(id), mapId, world, x, y, z, name);
        npcs.put(npc.id(), npc);
        markDirty();
        return npc;
    }

    public void remove(GpsNpc npc) {
        if (npcs.remove(npc.id()) != null) markDirty();
    }

    @Override
    protected void read(YamlConfiguration yaml) {
        npcs.clear();
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            GpsNpc npc = new GpsNpc(normalize(key), MapManager.normalize(s.getString("map", "default")),
                    s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    s.getString("name", key));
            npc.description(s.getStringList("description"));
            Material icon = Material.matchMaterial(s.getString("icon", "PLAYER_HEAD"));
            if (icon != null && icon.isItem()) npc.icon(icon);
            String linked = s.getString("linkedMarker");
            if (linked != null && !linked.isEmpty()) {
                try {
                    npc.linkedMarker(UUID.fromString(linked));
                } catch (IllegalArgumentException ignored) {
                }
            }
            String entity = s.getString("entity");
            if (entity != null && !entity.isEmpty()) {
                try {
                    npc.entityUuid(UUID.fromString(entity));
                } catch (IllegalArgumentException ignored) {
                }
            }
            npcs.put(npc.id(), npc);
        }
    }

    @Override
    protected void write(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.createSection("npcs");
        for (GpsNpc n : npcs.values()) {
            ConfigurationSection s = root.createSection(n.id());
            s.set("name", n.displayName());
            s.set("description", n.description());
            s.set("icon", n.icon().name());
            s.set("map", n.mapId());
            s.set("world", n.world());
            s.set("x", n.x());
            s.set("y", n.y());
            s.set("z", n.z());
            s.set("linkedMarker", n.linkedMarker() == null ? null : n.linkedMarker().toString());
            s.set("entity", n.entityUuid() == null ? null : n.entityUuid().toString());
        }
    }
}
