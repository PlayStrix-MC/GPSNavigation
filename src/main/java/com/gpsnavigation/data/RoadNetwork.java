package com.gpsnavigation.data;

import com.gpsnavigation.model.MarkerType;
import com.gpsnavigation.model.RoadMarker;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Граф дорожных маркеров в памяти (markers.jmc).
 * Пространственный индекс по чанкам ускоряет поиск соседей в радиусе.
 * Счётчик номеров сохраняется, поэтому короткие ID не переиспользуются после удаления.
 */
public final class RoadNetwork extends DataStore {

    private final Logger logger;
    private final Map<UUID, RoadMarker> markers = new LinkedHashMap<>();
    private final Map<Integer, RoadMarker> byNumber = new HashMap<>();
    private final Map<String, Map<Long, List<RoadMarker>>> grid = new HashMap<>();
    private int nextNumber = 1;

    public RoadNetwork(File dataFolder, Logger logger) {
        super(dataFolder, "markers", "GPSNavigation — маркеры дорожной сети (ключ — UUID маркера, neighbors — связи).\nФайл ведёт плагин; правьте при выключенном сервере или делайте /gps reload.");
        this.logger = logger;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private void index(RoadMarker m) {
        grid.computeIfAbsent(m.world(), w -> new HashMap<>())
                .computeIfAbsent(chunkKey(m.x() >> 4, m.z() >> 4), k -> new ArrayList<>())
                .add(m);
    }

    private void unindex(RoadMarker m) {
        Map<Long, List<RoadMarker>> w = grid.get(m.world());
        if (w == null) return;
        long key = chunkKey(m.x() >> 4, m.z() >> 4);
        List<RoadMarker> list = w.get(key);
        if (list != null) {
            list.remove(m);
            if (list.isEmpty()) w.remove(key);
        }
    }

    public RoadMarker get(UUID id) {
        return id == null ? null : markers.get(id);
    }

    /** Поиск по короткому ID ("M-007", "m7", "7") или UUID. */
    public RoadMarker find(String token) {
        if (token == null) return null;
        String t = token.trim().toUpperCase(Locale.ROOT);
        if (t.startsWith("M-")) t = t.substring(2);
        else if (t.startsWith("M")) t = t.substring(1);
        try {
            return byNumber.get(Integer.parseInt(t));
        } catch (NumberFormatException ignored) {
        }
        try {
            return markers.get(UUID.fromString(token.trim()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public Collection<RoadMarker> all() {
        return Collections.unmodifiableCollection(markers.values());
    }

    public List<RoadMarker> ofMap(String mapId) {
        List<RoadMarker> list = new ArrayList<>();
        for (RoadMarker m : markers.values()) {
            if (m.mapId().equals(mapId)) list.add(m);
        }
        return list;
    }

    public RoadMarker findAt(String world, int x, int y, int z) {
        Map<Long, List<RoadMarker>> w = grid.get(world);
        if (w == null) return null;
        List<RoadMarker> list = w.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (RoadMarker m : list) {
            if (m.isAt(world, x, y, z)) return m;
        }
        return null;
    }

    /** Маркеры, центр которых находится не дальше radius от точки (x, y, z). */
    public List<RoadMarker> nearby(String world, double x, double y, double z, double radius) {
        List<RoadMarker> result = new ArrayList<>();
        Map<Long, List<RoadMarker>> w = grid.get(world);
        if (w == null) return result;
        double r2 = radius * radius;
        int minCx = (int) Math.floor(x - radius) >> 4;
        int maxCx = (int) Math.floor(x + radius) >> 4;
        int minCz = (int) Math.floor(z - radius) >> 4;
        int maxCz = (int) Math.floor(z + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                List<RoadMarker> list = w.get(chunkKey(cx, cz));
                if (list == null) continue;
                for (RoadMarker m : list) {
                    if (m.distanceSq(x, y, z) <= r2) result.add(m);
                }
            }
        }
        return result;
    }

    /** Маркеры в вертикальном цилиндре: горизонтальное расстояние ≤ radius, высота любая. */
    public List<RoadMarker> nearbyColumn(String world, double x, double z, double radius) {
        List<RoadMarker> result = new ArrayList<>();
        Map<Long, List<RoadMarker>> w = grid.get(world);
        if (w == null) return result;
        double r2 = radius * radius;
        for (int cx = (int) Math.floor(x - radius) >> 4; cx <= (int) Math.floor(x + radius) >> 4; cx++) {
            for (int cz = (int) Math.floor(z - radius) >> 4; cz <= (int) Math.floor(z + radius) >> 4; cz++) {
                List<RoadMarker> list = w.get(chunkKey(cx, cz));
                if (list == null) continue;
                for (RoadMarker m : list) {
                    double dx = m.cx() - x;
                    double dz = m.cz() - z;
                    if (dx * dx + dz * dz <= r2) result.add(m);
                }
            }
        }
        return result;
    }

    public RoadMarker create(String mapId, String world, int x, int y, int z) {
        RoadMarker m = new RoadMarker(UUID.randomUUID(), nextNumber++, mapId, world, x, y, z);
        markers.put(m.uuid(), m);
        byNumber.put(m.number(), m);
        index(m);
        markDirty();
        return m;
    }

    /** Удаляет маркер и все его связи. Возвращает бывших соседей. */
    public List<RoadMarker> remove(RoadMarker m) {
        List<RoadMarker> former = new ArrayList<>();
        for (UUID n : new ArrayList<>(m.neighbors())) {
            RoadMarker other = markers.get(n);
            if (other != null) {
                other.neighbors().remove(m.uuid());
                former.add(other);
            }
        }
        m.neighbors().clear();
        markers.remove(m.uuid());
        byNumber.remove(m.number());
        unindex(m);
        markDirty();
        return former;
    }

    public boolean link(RoadMarker a, RoadMarker b) {
        if (a == b) return false;
        boolean changed = a.neighbors().add(b.uuid());
        changed |= b.neighbors().add(a.uuid());
        if (changed) markDirty();
        return changed;
    }

    public boolean unlink(RoadMarker a, RoadMarker b) {
        boolean changed = a.neighbors().remove(b.uuid());
        changed |= b.neighbors().remove(a.uuid());
        if (changed) markDirty();
        return changed;
    }

    public void removeMap(String mapId) {
        for (RoadMarker m : ofMap(mapId)) {
            remove(m);
        }
    }

    @Override
    protected void read(YamlConfiguration yaml) {
        markers.clear();
        byNumber.clear();
        grid.clear();
        nextNumber = Math.max(1, yaml.getInt("nextNumber", 1));
        ConfigurationSection root = yaml.getConfigurationSection("markers");
        Map<RoadMarker, List<String>> pendingLinks = new HashMap<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    logger.warning("markers.jmc: неверный UUID " + key + " — пропущен");
                    continue;
                }
                int number = s.getInt("number", 0);
                if (number <= 0 || byNumber.containsKey(number)) {
                    number = nextNumber;
                    markDirty();
                }
                RoadMarker m = new RoadMarker(uuid, number, MapManager.normalize(s.getString("map", "default")),
                        s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                try {
                    m.type(MarkerType.valueOf(s.getString("type", "ROAD").toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    m.type(MarkerType.ROAD);
                }
                ConfigurationSection settings = s.getConfigurationSection("settings");
                if (settings != null) m.settings().putAll(settings.getValues(false));
                markers.put(uuid, m);
                byNumber.put(m.number(), m);
                nextNumber = Math.max(nextNumber, m.number() + 1);
                index(m);
                pendingLinks.put(m, s.getStringList("neighbors"));
            }
        }
        // Восстанавливаем связи симметрично, отбрасывая ссылки на несуществующие маркеры.
        for (Map.Entry<RoadMarker, List<String>> e : pendingLinks.entrySet()) {
            for (String n : e.getValue()) {
                try {
                    RoadMarker other = markers.get(UUID.fromString(n));
                    if (other != null && other != e.getKey() && other.mapId().equals(e.getKey().mapId())) {
                        e.getKey().neighbors().add(other.uuid());
                        other.neighbors().add(e.getKey().uuid());
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    @Override
    protected void write(YamlConfiguration yaml) {
        yaml.set("nextNumber", nextNumber);
        ConfigurationSection root = yaml.createSection("markers");
        for (RoadMarker m : markers.values()) {
            ConfigurationSection s = root.createSection(m.uuid().toString());
            s.set("number", m.number());
            s.set("id", m.shortId());
            s.set("map", m.mapId());
            s.set("world", m.world());
            s.set("x", m.x());
            s.set("y", m.y());
            s.set("z", m.z());
            s.set("type", m.type().name());
            List<String> n = new ArrayList<>();
            for (Iterator<UUID> it = m.neighbors().iterator(); it.hasNext(); ) {
                n.add(it.next().toString());
            }
            java.util.Collections.sort(n);
            s.set("neighbors", n);
            s.createSection("settings", m.settings());
        }
    }
}
