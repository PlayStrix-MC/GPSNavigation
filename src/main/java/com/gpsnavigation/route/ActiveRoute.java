package com.gpsnavigation.route;

import com.gpsnavigation.model.RoadMarker;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Активный маршрут игрока: ломаная линия от игрока через маркеры к NPC. */
public final class ActiveRoute {

    final UUID playerId;
    final String npcId;
    final String mapId;
    final String world;

    List<Vector> points = new ArrayList<>();
    double[] cumulative = new double[0];
    List<RoadMarker> markers = new ArrayList<>();
    /** Индекс точки в points, где начинается дорожная часть (первый маркер). */
    int firstMarkerPoint = 1;

    /** Указатели по номеру позиции на маршруте (k * шаг): стоят на месте, пока игрок идёт. */
    final Map<Integer, PointerHandle> pointers = new HashMap<>();
    /** Сущность NPC, которой игрок видит свечение (через пакеты), и время последнего поиска. */
    UUID glowEntity;
    long lastEntityLookup;
    /** Запасной светящийся указатель над NPC, если сущность NPC не найдена. */
    PointerHandle npcMarker;

    Vector lastPosition;
    double progress;
    long lastRecalc;
    boolean needsRecalc;
    boolean warnedWorld;

    ActiveRoute(UUID playerId, String npcId, String mapId, String world) {
        this.playerId = playerId;
        this.npcId = npcId;
        this.mapId = mapId;
        this.world = world;
    }

    public String npcId() { return npcId; }
    public String mapId() { return mapId; }
    public List<RoadMarker> markers() { return markers; }
    public double progress() { return progress; }

    public double totalLength() {
        return cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
    }

    public double remaining() {
        return Math.max(0, totalLength() - progress);
    }

    void setPoints(List<Vector> pts) {
        this.points = pts;
        this.cumulative = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cumulative[i] = cumulative[i - 1] + pts.get(i - 1).distance(pts.get(i));
        }
    }

    /** Точка на маршруте на расстоянии s от начала. */
    Vector pointAt(double s) {
        if (points.isEmpty()) return null;
        if (s <= 0) return points.get(0).clone();
        for (int i = 1; i < points.size(); i++) {
            if (s <= cumulative[i]) {
                double seg = cumulative[i] - cumulative[i - 1];
                double t = seg <= 0 ? 0 : (s - cumulative[i - 1]) / seg;
                Vector a = points.get(i - 1);
                Vector b = points.get(i);
                return a.clone().add(b.clone().subtract(a).multiply(t));
            }
        }
        return points.get(points.size() - 1).clone();
    }

    /** Проекция позиции игрока на маршрут: {пройденное расстояние, отклонение}. */
    double[] project(Vector p) {
        double bestDist = Double.MAX_VALUE;
        double bestS = 0;
        for (int i = 1; i < points.size(); i++) {
            Vector a = points.get(i - 1);
            Vector b = points.get(i);
            Vector ab = b.clone().subtract(a);
            double len2 = ab.lengthSquared();
            double t = len2 == 0 ? 0 : p.clone().subtract(a).dot(ab) / len2;
            t = Math.max(0, Math.min(1, t));
            Vector c = a.clone().add(ab.multiply(t));
            // Вертикальное отклонение весит меньше: игрок мог подняться на ступеньку.
            double dx = c.getX() - p.getX();
            double dy = (c.getY() - p.getY()) * 0.5;
            double dz = c.getZ() - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                bestS = cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * t;
            }
        }
        return new double[]{bestS, Math.sqrt(bestDist)};
    }
}
