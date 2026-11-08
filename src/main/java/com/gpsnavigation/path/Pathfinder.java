package com.gpsnavigation.path;

import com.gpsnavigation.data.RoadNetwork;
import com.gpsnavigation.data.ZoneManager;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.RoadMarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * A* по графу маркеров одной карты.
 * Старт многоточечный: в очередь кладутся маркеры вокруг игрока со стоимостью = расстояние × штраф бездорожья,
 * поэтому обычно выбирается ближайший маркер, а более дальний — только если это заметно сокращает путь.
 * Маркеры внутри мёртвых зон и связи, проходящие через них, исключаются.
 */
public final class Pathfinder {

    private Pathfinder() {
    }

    private static final class Node implements Comparable<Node> {
        final UUID id;
        final double f;

        Node(UUID id, double f) {
            this.id = id;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    public static List<RoadMarker> find(RoadNetwork network, ZoneManager zones, String mapId, String world,
                                        double sx, double sy, double sz, double startRadius, double offroadPenalty,
                                        RoadMarker goal) {
        return find(network, zones, mapId, world, sx, sy, sz, startRadius, goal,
                m -> Math.sqrt(m.distanceSq(sx, sy, sz)) * offroadPenalty);
    }

    /**
     * @param startCost стоимость пути от игрока до маркера (бездорожье, с обходом зон);
     *                  {@code Double.POSITIVE_INFINITY} — к маркеру не пройти
     */
    public static List<RoadMarker> find(RoadNetwork network, ZoneManager zones, String mapId, String world,
                                        double sx, double sy, double sz, double startRadius,
                                        RoadMarker goal, java.util.function.ToDoubleFunction<RoadMarker> startCost) {
        if (goal == null || !goal.mapId().equals(mapId)) return null;
        List<DeadZone> mapZones = zones.ofMap(mapId);
        if (zones.isBlocked(goal, mapZones)) return null;

        List<RoadMarker> starts = new ArrayList<>();
        for (RoadMarker m : network.nearby(world, sx, sy, sz, startRadius)) {
            if (m.mapId().equals(mapId) && !zones.isBlocked(m, mapZones)) starts.add(m);
        }
        if (starts.isEmpty()) {
            // Рядом дорог нет — берём до 24 ближайших маркеров карты (дальше решит стоимость с обходом зон).
            List<RoadMarker> all = new ArrayList<>();
            for (RoadMarker m : network.ofMap(mapId)) {
                if (m.world().equals(world) && !zones.isBlocked(m, mapZones)) all.add(m);
            }
            all.sort(java.util.Comparator.comparingDouble(m -> m.distanceSq(sx, sy, sz)));
            starts.addAll(all.subList(0, Math.min(24, all.size())));
            if (starts.isEmpty()) return null;
        }

        Map<UUID, Double> g = new HashMap<>();
        Map<UUID, UUID> parent = new HashMap<>();
        Set<UUID> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        for (RoadMarker s : starts) {
            // Путь до дороги "по бездорожью" дороже, поэтому обычно выбирается ближайший маркер.
            double cost = startCost.applyAsDouble(s);
            if (Double.isInfinite(cost) || Double.isNaN(cost)) continue;
            g.put(s.uuid(), cost);
            open.add(new Node(s.uuid(), cost + s.distance(goal)));
        }

        while (!open.isEmpty()) {
            Node node = open.poll();
            if (!closed.add(node.id)) continue;
            RoadMarker current = network.get(node.id);
            if (current == null) continue;
            if (current == goal) {
                List<RoadMarker> path = new ArrayList<>();
                UUID step = current.uuid();
                while (step != null) {
                    path.add(network.get(step));
                    step = parent.get(step);
                }
                Collections.reverse(path);
                return path;
            }
            double base = g.get(current.uuid());
            for (UUID nid : current.neighbors()) {
                if (closed.contains(nid)) continue;
                RoadMarker next = network.get(nid);
                if (next == null || !next.mapId().equals(mapId)) continue;
                if (zones.isBlocked(next, mapZones) || zones.segmentBlocked(current, next, mapZones)) continue;
                double ng = base + current.distance(next);
                Double old = g.get(nid);
                if (old == null || ng < old) {
                    g.put(nid, ng);
                    parent.put(nid, current.uuid());
                    open.add(new Node(nid, ng + next.distance(goal)));
                }
            }
        }
        return null;
    }
}
