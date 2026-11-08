package com.gpsnavigation.data;

import com.gpsnavigation.model.TerrainMap;
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

/** Карты местности (maps.jmc). */
public final class MapManager extends DataStore {

    private final Map<String, TerrainMap> maps = new LinkedHashMap<>();

    public MapManager(File dataFolder) {
        super(dataFolder, "maps", "GPSNavigation — карты местности. Файл ведёт плагин; правьте при выключенном сервере или делайте /gps reload.");
    }

    public static String normalize(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    public TerrainMap get(String id) {
        return maps.get(normalize(id));
    }

    public Collection<TerrainMap> all() {
        return maps.values();
    }

    public List<TerrainMap> inWorld(String world) {
        List<TerrainMap> list = new ArrayList<>();
        for (TerrainMap m : maps.values()) {
            if (m.world().equals(world)) list.add(m);
        }
        return list;
    }

    public TerrainMap create(String id, String world, String displayName) {
        TerrainMap map = new TerrainMap(normalize(id), world, displayName);
        maps.put(map.id(), map);
        markDirty();
        return map;
    }

    public void remove(String id) {
        if (maps.remove(normalize(id)) != null) markDirty();
    }

    @Override
    protected void read(YamlConfiguration yaml) {
        maps.clear();
        ConfigurationSection root = yaml.getConfigurationSection("maps");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            TerrainMap map = new TerrainMap(normalize(key), s.getString("world", "world"), s.getString("name", key));
            map.description(s.getStringList("description"));
            Material icon = Material.matchMaterial(s.getString("icon", "FILLED_MAP"));
            if (icon != null && icon.isItem()) map.icon(icon);
            maps.put(map.id(), map);
        }
    }

    @Override
    protected void write(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.createSection("maps");
        for (TerrainMap m : maps.values()) {
            ConfigurationSection s = root.createSection(m.id());
            s.set("name", m.displayName());
            s.set("world", m.world());
            s.set("description", m.description());
            s.set("icon", m.icon().name());
        }
    }
}
