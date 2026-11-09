package com.gpsnavigation.path;

import com.gpsnavigation.data.RoadNetwork;
import com.gpsnavigation.data.ZoneManager;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.RoadMarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Единый A* от игрока до NPC.
 * <p>
 * Вершины графа:
 * <ul>
 *   <li>старт — позиция игрока;</li>
 *   <li>узлы обхода — вершины контуров мёртвых зон, отодвинутые наружу на 1.5 блока;</li>
 *   <li>маркеры дорог.</li>
 * </ul>
 * Рёбра:
 * <ul>
 *   <li>по бездорожью (только от старта и между узлами обхода, к маркеру или узлу обхода) — прямая,
 *       не пересекающая ни одну мёртвую зону; стоимость = длина × штраф бездорожья;</li>
 *   <li>по дороге (маркер → соседний маркер) — связь, не проходящая через мёртвую зону; стоимость = длина.</li>
 * </ul>
 * Если задан {@link Walker}, участок по бездорожью допускается, только если по нему можно пройти ногами
 * (без стен и обрывов), а выход на маркер — если дошли до его высоты.
 * Сойти с дороги обратно на бездорожье нельзя. Так A* одним поиском выбирает сторону обхода зоны,
 * точку входа на дорогу и путь по дороге — с наименьшей общей стоимостью.
 */
public final class RoutePlanner {

    private static final double OFFSET = 1.5;
    private static final int MAX_CONTOUR_NODES = 800;
    private static final int FAR_CANDIDATES = 24;

    /** Проход по прямой по рельефу: высота в конце пути или NaN, если пройти нельзя. */
    public interface Walker {
        double walk(double ax, double ay, double az, double bx, double bz);
    }

    /** Результат: точки бездорожья (без старта) и маркеры дороги по порядку. */
    public static final class Result {
        public final List<double[]> offroad;
        public final List<RoadMarker> markers;

        Result(List<double[]> offroad, List<RoadMarker> markers) {
            this.offroad = offroad;
            this.markers = markers;
        }
    }

    private final RoadNetwork network;
    private final ZoneManager zoneManager;
    private final String mapId;
    private final String world;
    private final List<DeadZone> zones;
    private final double sx;
    private final double sy;
    private final double sz;
    private final double penalty;
    private final ToDoubleFunction<RoadMarker> directExtra;
    private final Walker walker;
    private final double startY;
    private final List<double[]> contour = new ArrayList<>();
    private final List<RoadMarker> entries = new ArrayList<>();

    /**
     * @param directExtra множитель для прямого выхода от игрока к маркеру (например, 1.5, если маркер закрыт рельефом)
     */
    public RoutePlanner(RoadNetwork network, ZoneManager zones, String mapId, String world,
                        double sx, double sy, double sz, double startRadius, double offroadPenalty,
                        ToDoubleFunction<RoadMarker> directExtra, double startY, Walker walker) {
        this.network = network;
        this.zoneManager = zones;
        this.mapId = mapId;
        this.world = world;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.penalty = offroadPenalty;
        this.directExtra = directExtra;
        this.startY = startY;
        this.walker = walker;
        boolean inside = false;
        List<DeadZone> all = zones.ofMap(mapId);
        for (DeadZone z : all) {
            if (z.contains(sx, sz)) inside = true;
        }
        // Игрок уже внутри зоны — обход не строим, просто выводим к ближайшей дороге.
        this.zones = inside ? List.of() : all;
        for (DeadZone z : this.zones) addContour(z);
        if (contour.size() > MAX_CONTOUR_NODES) contour.subList(MAX_CONTOUR_NODES, contour.size()).clear();
        collectEntries(startRadius, all);
    }

    // ---------- Подготовка ----------

    private void addContour(DeadZone z) {
        List<double[]> poly = z.polygon();
        int n = poly.size();
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            double[] v = poly.get(i);
            double[] p = poly.get((i - 1 + n) % n);
            double[] q = poly.get((i + 1) % n);
            double ax = v[0] - p[0], az = v[1] - p[1];
            double bx = v[0] - q[0], bz = v[1] - q[1];
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            if (la < 1e-6 || lb < 1e-6) continue;
            double dx = ax / la + bx / lb;
            double dz = az / la + bz / lb;
            double l = Math.hypot(dx, dz);
            if (l < 1e-6) {
                dx = -(q[1] - p[1]);
                dz = q[0] - p[0];
                l = Math.hypot(dx, dz);
                if (l < 1e-6) continue;
            }
            dx /= l;
            dz /= l;
            double[] out = {v[0] + dx * OFFSET, v[1] + dz * OFFSET};
            if (insideAny(out[0], out[1])) out = new double[]{v[0] - dx * OFFSET, v[1] - dz * OFFSET};
            if (!insideAny(out[0], out[1])) contour.add(out);
        }
    }

    /** Маркеры, на которые можно выйти с бездорожья: рядом с игроком, рядом с узлами обхода и ближайшие на карте. */
    private void collectEntries(double startRadius, List<DeadZone> allZones) {
        Set<RoadMarker> set = new LinkedHashSet<>();
        for (RoadMarker m : network.nearby(world, sx, sy, sz, startRadius)) {
            if (m.mapId().equals(mapId) && !blocked(m, allZones)) set.add(m);
        }
        for (double[] c : contour) {
            for (RoadMarker m : network.nearbyColumn(world, c[0], c[1], startRadius)) {
                if (m.mapId().equals(mapId) && !blocked(m, allZones)) set.add(m);
            }
        }
        List<RoadMarker> far = new ArrayList<>();
        for (RoadMarker m : network.ofMap(mapId)) {
            if (m.world().equals(world) && !blocked(m, allZones)) far.add(m);
        }
        far.sort(java.util.Comparator.comparingDouble(m -> m.distanceSq(sx, sy, sz)));
        set.addAll(far.subList(0, Math.min(FAR_CANDIDATES, far.size())));
        entries.addAll(set);
    }

    private boolean blocked(RoadMarker m, List<DeadZone> allZones) {
        return zoneManager.isBlocked(m, allZones);
    }

    private boolean insideAny(double x, double z) {
        for (DeadZone dz : zones) {
            if (dz.contains(x, z)) return true;
        }
        return false;
    }

    private boolean clear(double ax, double az, double bx, double bz) {
        for (DeadZone dz : zones) {
            if (dz.intersectsSegment(ax, az, bx, bz)) return false;
        }
        return true;
    }

    // ---------- A* ----------

    /*
     * Ключи вершин: "S" — старт, "C<i>" — узел обхода, UUID маркера — маркер.
     */
    private static final class Node implements Comparable<Node> {
        final String key;
        final double f;

        Node(String key, double f) {
            this.key = key;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    public Result find(RoadMarker goal) {
        if (goal == null || !goal.mapId().equals(mapId)) return null;
        List<DeadZone> allZones = zoneManager.ofMap(mapId);
        if (zoneManager.isBlocked(goal, allZones)) return null;

        Map<String, Double> g = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        Map<String, Double> nodeY = new HashMap<>();
        Set<String> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        g.put("S", 0.0);
        open.add(new Node("S", h(sx, sy, sz, goal)));

        while (!open.isEmpty()) {
            Node node = open.poll();
            if (!closed.add(node.key)) continue;
            double base = g.get(node.key);

            if (node.key.equals("S") || node.key.charAt(0) == 'C') {
                double[] from = node.key.equals("S") ? new double[]{sx, sz} : contour.get(Integer.parseInt(node.key.substring(1)));
                boolean isStart = node.key.equals("S");
                double fromY = isStart ? startY : nodeY.getOrDefault(node.key, startY);
                // Бездорожье → узлы обхода
                for (int i = 0; i < contour.size(); i++) {
                    String k = "C" + i;
                    if (closed.contains(k)) continue;
                    double[] c = contour.get(i);
                    double d = Math.hypot(c[0] - from[0], c[1] - from[1]);
                    double ng = base + d * penalty;
                    if (ng >= g.getOrDefault(k, Double.POSITIVE_INFINITY)) continue;
                    if (!clear(from[0], from[1], c[0], c[1])) continue;
                    double ey = fromY;
                    if (walker != null) {
                        ey = walker.walk(from[0], fromY, from[1], c[0], c[1]);
                        if (Double.isNaN(ey)) continue;
                    }
                    nodeY.put(k, ey);
                    g.put(k, ng);
                    parent.put(k, node.key);
                    open.add(new Node(k, ng + h(c[0], fromY, c[1], goal)));
                }
                // Бездорожье → выход на дорогу
                for (RoadMarker m : entries) {
                    String k = m.uuid().toString();
                    if (closed.contains(k)) continue;
                    double dx = m.cx() - from[0], dz = m.cz() - from[1], dy = m.y() - fromY;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double extra = isStart ? directExtra.applyAsDouble(m) : 1.0;
                    double ng = base + d * penalty * extra;
                    if (ng >= g.getOrDefault(k, Double.POSITIVE_INFINITY)) continue;
                    if (!clear(from[0], from[1], m.cx(), m.cz())) continue;
                    if (walker != null) {
                        double ey = walker.walk(from[0], fromY, from[1], m.cx(), m.cz());
                        if (Double.isNaN(ey) || Math.abs(ey - m.y()) > 1.5) continue;
                    }
                    g.put(k, ng);
                    parent.put(k, node.key);
                    open.add(new Node(k, ng + m.distance(goal)));
                }
                continue;
            }

            RoadMarker current = network.get(UUID.fromString(node.key));
            if (current == null) continue;
            if (current == goal) return build(node.key, parent);
            for (UUID nid : current.neighbors()) {
                String k = nid.toString();
                if (closed.contains(k)) continue;
                RoadMarker next = network.get(nid);
                if (next == null || !next.mapId().equals(mapId)) continue;
                if (zoneManager.isBlocked(next, allZones) || zoneManager.segmentBlocked(current, next, allZones)) continue;
                double ng = base + current.distance(next);
                if (ng < g.getOrDefault(k, Double.POSITIVE_INFINITY)) {
                    g.put(k, ng);
                    parent.put(k, node.key);
                    open.add(new Node(k, ng + next.distance(goal)));
                }
            }
        }
        return null;
    }

    private static double h(double x, double y, double z, RoadMarker goal) {
        double dx = goal.cx() - x, dy = goal.cy() - y, dz = goal.cz() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Result build(String last, Map<String, String> parent) {
        List<String> keys = new ArrayList<>();
        for (String k = last; k != null; k = parent.get(k)) keys.add(k);
        Collections.reverse(keys);
        List<double[]> offroad = new ArrayList<>();
        List<RoadMarker> markers = new ArrayList<>();
        for (String k : keys) {
            if (k.equals("S")) continue;
            if (k.charAt(0) == 'C') offroad.add(contour.get(Integer.parseInt(k.substring(1))));
            else markers.add(network.get(UUID.fromString(k)));
        }
        return new Result(offroad, markers);
    }
}
