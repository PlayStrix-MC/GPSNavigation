package com.gpsnavigation.data;

import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.ZonePoint;
import com.gpsnavigation.util.OuterBoundary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Точки мёртвых зон (zone-points.jmc) и вычисленные из них зоны.
 * Группа связанных точек, образующая замкнутый контур, даёт мёртвую зону — всю площадь внутри контура.
 * Зоны пересчитываются лениво и кэшируются по карте.
 */
public final class ZoneManager extends DataStore {

    /** Минимальная площадь (блоков²), чтобы контур считался замкнутым. */
    private static final double MIN_AREA = 2.0;
    /**
     * Минимальная «толщина» контура (площадь / периметр). Отсекает узкие полоски из треугольников,
     * которые возникают, когда соседние точки цепочки соединены и между собой, но кольцо не замкнуто.
     */
    private static final double MIN_THICKNESS = 0.5;

    private final Logger logger;
    private final Map<UUID, ZonePoint> points = new LinkedHashMap<>();
    private final Map<Integer, ZonePoint> byNumber = new HashMap<>();
    private final Map<String, Map<Long, List<ZonePoint>>> grid = new HashMap<>();
    private final Map<String, List<DeadZone>> areaCache = new HashMap<>();
    private final Map<UUID, DeadZone> pointArea = new HashMap<>();
    private int nextNumber = 1;

    public ZoneManager(File dataFolder, Logger logger) {
        super(dataFolder, "zone-points", "GPSNavigation — точки мёртвых зон (замкнутый контур из связанных точек = мёртвая зона).\nФайл ведёт плагин; правьте при выключенном сервере или делайте /gps reload.");
        this.logger = logger;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private void index(ZonePoint p) {
        grid.computeIfAbsent(p.world(), w -> new HashMap<>())
                .computeIfAbsent(chunkKey(p.x() >> 4, p.z() >> 4), k -> new ArrayList<>()).add(p);
    }

    private void unindex(ZonePoint p) {
        Map<Long, List<ZonePoint>> w = grid.get(p.world());
        if (w == null) return;
        long key = chunkKey(p.x() >> 4, p.z() >> 4);
        List<ZonePoint> list = w.get(key);
        if (list != null) {
            list.remove(p);
            if (list.isEmpty()) w.remove(key);
        }
    }

    private void invalidate(String mapId) {
        List<DeadZone> old = areaCache.remove(mapId);
        if (old != null) {
            for (DeadZone z : old) {
                for (ZonePoint p : z.members()) pointArea.remove(p.uuid());
            }
        }
    }

    // ---------- Точки ----------

    public Collection<ZonePoint> points() {
        return Collections.unmodifiableCollection(points.values());
    }

    public ZonePoint get(UUID id) {
        return id == null ? null : points.get(id);
    }

    public ZonePoint find(String token) {
        if (token == null) return null;
        String t = token.trim().toUpperCase(Locale.ROOT);
        if (t.startsWith("Z-")) t = t.substring(2);
        else if (t.startsWith("Z")) t = t.substring(1);
        try {
            return byNumber.get(Integer.parseInt(t));
        } catch (NumberFormatException ignored) {
        }
        try {
            return points.get(UUID.fromString(token.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<ZonePoint> pointsOfMap(String mapId) {
        List<ZonePoint> list = new ArrayList<>();
        for (ZonePoint p : points.values()) {
            if (p.mapId().equals(mapId)) list.add(p);
        }
        return list;
    }

    public ZonePoint findAt(String world, int x, int y, int z) {
        Map<Long, List<ZonePoint>> w = grid.get(world);
        if (w == null) return null;
        List<ZonePoint> list = w.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (ZonePoint p : list) {
            if (p.isAt(world, x, y, z)) return p;
        }
        return null;
    }

    public List<ZonePoint> nearby(String world, double x, double y, double z, double radius) {
        List<ZonePoint> result = new ArrayList<>();
        Map<Long, List<ZonePoint>> w = grid.get(world);
        if (w == null) return result;
        double r2 = radius * radius;
        for (int cx = (int) Math.floor(x - radius) >> 4; cx <= (int) Math.floor(x + radius) >> 4; cx++) {
            for (int cz = (int) Math.floor(z - radius) >> 4; cz <= (int) Math.floor(z + radius) >> 4; cz++) {
                List<ZonePoint> list = w.get(chunkKey(cx, cz));
                if (list == null) continue;
                for (ZonePoint p : list) {
                    if (p.distanceSq(x, y, z) <= r2) result.add(p);
                }
            }
        }
        return result;
    }

    /** Точки в вертикальном цилиндре: горизонтальное расстояние ≤ radius, высота любая. */
    public List<ZonePoint> nearbyColumn(String world, double x, double z, double radius) {
        List<ZonePoint> result = new ArrayList<>();
        Map<Long, List<ZonePoint>> w = grid.get(world);
        if (w == null) return result;
        double r2 = radius * radius;
        for (int cx = (int) Math.floor(x - radius) >> 4; cx <= (int) Math.floor(x + radius) >> 4; cx++) {
            for (int cz = (int) Math.floor(z - radius) >> 4; cz <= (int) Math.floor(z + radius) >> 4; cz++) {
                List<ZonePoint> list = w.get(chunkKey(cx, cz));
                if (list == null) continue;
                for (ZonePoint p : list) {
                    double dx = p.cx() - x;
                    double dz = p.cz() - z;
                    if (dx * dx + dz * dz <= r2) result.add(p);
                }
            }
        }
        return result;
    }

    public ZonePoint create(String mapId, String world, int x, int y, int z) {
        ZonePoint p = new ZonePoint(UUID.randomUUID(), nextNumber++, mapId, world, x, y, z);
        points.put(p.uuid(), p);
        byNumber.put(p.number(), p);
        index(p);
        invalidate(mapId);
        markDirty();
        return p;
    }

    public int remove(ZonePoint p) {
        int links = 0;
        for (UUID n : new ArrayList<>(p.neighbors())) {
            ZonePoint o = points.get(n);
            if (o != null) {
                o.neighbors().remove(p.uuid());
                links++;
            }
        }
        p.neighbors().clear();
        points.remove(p.uuid());
        byNumber.remove(p.number());
        unindex(p);
        invalidate(p.mapId());
        markDirty();
        return links;
    }

    public boolean link(ZonePoint a, ZonePoint b) {
        if (a == b || !a.mapId().equals(b.mapId())) return false;
        boolean changed = a.neighbors().add(b.uuid());
        changed |= b.neighbors().add(a.uuid());
        if (changed) {
            invalidate(a.mapId());
            markDirty();
        }
        return changed;
    }

    public boolean unlink(ZonePoint a, ZonePoint b) {
        boolean changed = a.neighbors().remove(b.uuid());
        changed |= b.neighbors().remove(a.uuid());
        if (changed) {
            invalidate(a.mapId());
            markDirty();
        }
        return changed;
    }

    public void removeMap(String mapId) {
        for (ZonePoint p : pointsOfMap(mapId)) remove(p);
    }

    // ---------- Зоны (вычисляемые) ----------

    /** Мёртвые зоны карты: замкнутые контуры из связанных точек. */
    public List<DeadZone> ofMap(String mapId) {
        List<DeadZone> cached = areaCache.get(mapId);
        if (cached != null) return cached;
        List<DeadZone> zones = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (ZonePoint start : pointsOfMap(mapId)) {
            if (visited.contains(start.uuid())) continue;
            // Связная группа точек.
            List<ZonePoint> group = new ArrayList<>();
            ArrayDeque<ZonePoint> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start.uuid());
            while (!queue.isEmpty()) {
                ZonePoint p = queue.poll();
                group.add(p);
                for (UUID n : p.neighbors()) {
                    ZonePoint o = points.get(n);
                    if (o != null && o.mapId().equals(mapId) && visited.add(o.uuid())) queue.add(o);
                }
            }
            if (group.size() < 3) continue;
            List<double[]> edges = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ZonePoint p : group) {
                for (UUID n : p.neighbors()) {
                    ZonePoint o = points.get(n);
                    if (o == null) continue;
                    String key = p.uuid().compareTo(o.uuid()) < 0 ? p.uuid() + ":" + o.uuid() : o.uuid() + ":" + p.uuid();
                    if (!seen.add(key)) continue;
                    if (Math.abs(p.cx() - o.cx()) < 1e-9 && Math.abs(p.cz() - o.cz()) < 1e-9) continue;
                    edges.add(new double[]{p.cx(), p.cz(), o.cx(), o.cz()});
                }
            }
            List<double[]> boundary = OuterBoundary.compute(edges);
            double area = boundary.size() >= 3 ? OuterBoundary.area(boundary) : 0;
            if (area < MIN_AREA || area / OuterBoundary.perimeter(boundary) < MIN_THICKNESS) continue;
            DeadZone zone = new DeadZone(mapId, group.get(0).world(), boundary, group, area);
            zones.add(zone);
            for (ZonePoint p : group) pointArea.put(p.uuid(), zone);
        }
        areaCache.put(mapId, zones);
        return zones;
    }

    /** Зона, которую замыкает эта точка, или null (контур не замкнут). */
    public DeadZone zoneOf(ZonePoint p) {
        ofMap(p.mapId());
        return pointArea.get(p.uuid());
    }

    public List<DeadZone> inWorld(String world, Collection<String> mapIds) {
        List<DeadZone> list = new ArrayList<>();
        for (String m : mapIds) {
            for (DeadZone z : ofMap(m)) {
                if (z.world().equals(world)) list.add(z);
            }
        }
        return list;
    }

    public DeadZone zoneAt(String mapId, double x, double z) {
        for (DeadZone zone : ofMap(mapId)) {
            if (zone.contains(x, z)) return zone;
        }
        return null;
    }

    public boolean isBlocked(RoadMarker m) {
        return zoneAt(m.mapId(), m.cx(), m.cz()) != null;
    }

    public boolean isBlocked(RoadMarker m, List<DeadZone> mapZones) {
        for (DeadZone z : mapZones) {
            if (z.contains(m.cx(), m.cz())) return true;
        }
        return false;
    }

    public boolean segmentBlocked(RoadMarker a, RoadMarker b) {
        return segmentBlocked(a, b, ofMap(a.mapId()));
    }

    public boolean segmentBlocked(RoadMarker a, RoadMarker b, List<DeadZone> mapZones) {
        for (DeadZone z : mapZones) {
            if (z.intersectsSegment(a.cx(), a.cz(), b.cx(), b.cz())) return true;
        }
        return false;
    }

    // ---------- YAML ----------

    @Override
    protected void read(YamlConfiguration yaml) {
        points.clear();
        byNumber.clear();
        grid.clear();
        areaCache.clear();
        pointArea.clear();
        nextNumber = Math.max(1, yaml.getInt("nextNumber", 1));
        ConfigurationSection root = yaml.getConfigurationSection("points");
        Map<ZonePoint, List<String>> pending = new HashMap<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    logger.warning("zone-points.jmc: неверный UUID " + key);
                    continue;
                }
                int number = s.getInt("number", 0);
                if (number <= 0 || byNumber.containsKey(number)) {
                    number = nextNumber;
                    markDirty();
                }
                ZonePoint p = new ZonePoint(uuid, number, MapManager.normalize(s.getString("map", "default")),
                        s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                points.put(uuid, p);
                byNumber.put(number, p);
                nextNumber = Math.max(nextNumber, number + 1);
                index(p);
                pending.put(p, s.getStringList("neighbors"));
            }
        }
        for (Map.Entry<ZonePoint, List<String>> e : pending.entrySet()) {
            for (String n : e.getValue()) {
                try {
                    ZonePoint o = points.get(UUID.fromString(n));
                    if (o != null && o != e.getKey() && o.mapId().equals(e.getKey().mapId())) {
                        e.getKey().neighbors().add(o.uuid());
                        o.neighbors().add(e.getKey().uuid());
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    @Override
    protected void write(YamlConfiguration yaml) {
        yaml.set("nextNumber", nextNumber);
        ConfigurationSection root = yaml.createSection("points");
        for (ZonePoint p : points.values()) {
            ConfigurationSection s = root.createSection(p.uuid().toString());
            s.set("number", p.number());
            s.set("id", p.shortId());
            s.set("map", p.mapId());
            s.set("world", p.world());
            s.set("x", p.x());
            s.set("y", p.y());
            s.set("z", p.z());
            List<String> n = new ArrayList<>();
            for (UUID id : p.neighbors()) n.add(id.toString());
            java.util.Collections.sort(n);
            s.set("neighbors", n);
        }
    }
}
