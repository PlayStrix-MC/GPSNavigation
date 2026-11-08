package com.gpsnavigation.util;

/** Простая 2D-геометрия (плоскость XZ) для мёртвых зон. */
public final class Geometry {

    private Geometry() {
    }

    public static double distToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx - px;
        double cz = az + t * dz - pz;
        return cx * cx + cz * cz;
    }

    private static double cross(double ax, double az, double bx, double bz, double cx, double cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
        return Math.min(ax, bx) - 1e-9 <= px && px <= Math.max(ax, bx) + 1e-9
                && Math.min(az, bz) - 1e-9 <= pz && pz <= Math.max(az, bz) + 1e-9;
    }

    public static boolean segmentsIntersect(double ax, double az, double bx, double bz,
                                            double cx, double cz, double dx, double dz) {
        double d1 = cross(cx, cz, dx, dz, ax, az);
        double d2 = cross(cx, cz, dx, dz, bx, bz);
        double d3 = cross(ax, az, bx, bz, cx, cz);
        double d4 = cross(ax, az, bx, bz, dx, dz);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(cx, cz, dx, dz, ax, az)) return true;
        if (d2 == 0 && onSegment(cx, cz, dx, dz, bx, bz)) return true;
        if (d3 == 0 && onSegment(ax, az, bx, bz, cx, cz)) return true;
        return d4 == 0 && onSegment(ax, az, bx, bz, dx, dz);
    }

    /** Пересекает ли отрезок прямоугольник [minX,maxX]x[minZ,maxZ] (алгоритм Лианга-Барски). */
    public static boolean segmentIntersectsRect(double ax, double az, double bx, double bz,
                                                double minX, double minZ, double maxX, double maxZ) {
        double t0 = 0;
        double t1 = 1;
        double dx = bx - ax;
        double dz = bz - az;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {ax - minX, maxX - ax, az - minZ, maxZ - az};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return false;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return false;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return false;
                    if (r < t1) t1 = r;
                }
            }
        }
        return true;
    }

    public static boolean pointInPolygon(double x, double z, java.util.List<double[]> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i)[0], zi = poly.get(i)[1];
            double xj = poly.get(j)[0], zj = poly.get(j)[1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
