package com.gpsnavigation.model;

import com.gpsnavigation.util.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Мёртвая зона — площадь (на всех высотах), замкнутая соединёнными точками зоны.
 * Вычисляется из графа точек: внешняя граница группы связанных точек.
 */
public final class DeadZone {

    private final String mapId;
    private final String world;
    private final List<double[]> polygon;
    private final List<ZonePoint> members;
    private final String label;
    private final double area;
    private final double minX;
    private final double minZ;
    private final double maxX;
    private final double maxZ;

    public DeadZone(String mapId, String world, List<double[]> polygon, List<ZonePoint> members, double area) {
        this.mapId = mapId;
        this.world = world;
        this.polygon = polygon;
        this.members = members;
        this.area = area;
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (double[] p : polygon) {
            a = Math.min(a, p[0]);
            b = Math.min(b, p[1]);
            c = Math.max(c, p[0]);
            d = Math.max(d, p[1]);
        }
        minX = a;
        minZ = b;
        maxX = c;
        maxZ = d;
        ZonePoint first = null;
        for (ZonePoint p : members) {
            if (first == null || p.number() < first.number()) first = p;
        }
        label = first == null ? "?" : first.shortId();
    }

    public String mapId() { return mapId; }
    public String world() { return world; }
    public List<double[]> polygon() { return polygon; }
    public List<ZonePoint> members() { return members; }
    public double area() { return area; }

    /** Обозначение зоны: ID точки с наименьшим номером. */
    public String shortId() { return label; }

    public double[] bounds() {
        return new double[]{minX, minZ, maxX, maxZ};
    }

    public boolean contains(double x, double z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        return Geometry.pointInPolygon(x, z, polygon);
    }

    public boolean intersectsSegment(double ax, double az, double bx, double bz) {
        if (Math.max(ax, bx) < minX || Math.min(ax, bx) > maxX || Math.max(az, bz) < minZ || Math.min(az, bz) > maxZ) {
            return false;
        }
        if (contains(ax, az) || contains(bx, bz)) return true;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double[] p = polygon.get(j);
            double[] q = polygon.get(i);
            if (Geometry.segmentsIntersect(ax, az, bx, bz, p[0], p[1], q[0], q[1])) return true;
        }
        return false;
    }

    public String describe() {
        return "точек: " + members.size() + ", площадь ≈ " + Math.round(area) + " блоков²";
    }

    public List<String> memberIds() {
        List<String> ids = new ArrayList<>();
        for (ZonePoint p : members) ids.add(p.shortId());
        return ids;
    }
}
