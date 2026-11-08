package com.gpsnavigation.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Внешняя граница плоского графа (плоскость XZ).
 * Пересекающиеся рёбра сначала разбиваются в точках пересечения, «хвосты» без циклов отбрасываются,
 * затем выполняется обход внешней грани
 * («правило самого правого поворота»). Всё, что внутри этой границы, считается замкнутой площадью.
 */
public final class OuterBoundary {

    private OuterBoundary() {
    }

    /**
     * @param edges рёбра {ax, az, bx, bz}
     * @return вершины внешней границы (может быть вырожденной, если цикла нет — тогда площадь ≈ 0)
     */
    public static List<double[]> compute(List<double[]> edges) {
        // 1. Разбиваем рёбра в точках взаимного пересечения.
        List<List<Double>> splits = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            List<Double> ts = new ArrayList<>();
            ts.add(0.0);
            ts.add(1.0);
            splits.add(ts);
        }
        for (int i = 0; i < edges.size(); i++) {
            double[] e = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                double[] f = edges.get(j);
                double[] t = intersect(e, f);
                if (t != null) {
                    splits.get(i).add(t[0]);
                    splits.get(j).add(t[1]);
                }
            }
        }
        // 2. Строим граф с объединением совпадающих вершин.
        Map<Long, Integer> index = new HashMap<>();
        List<double[]> vertices = new ArrayList<>();
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            double[] e = edges.get(i);
            List<Double> ts = splits.get(i);
            ts.sort(Double::compare);
            int prev = -1;
            for (double t : ts) {
                int v = vertex(e[0] + (e[2] - e[0]) * t, e[1] + (e[3] - e[1]) * t, index, vertices, adj);
                if (prev >= 0 && prev != v && !adj.get(prev).contains(v)) {
                    adj.get(prev).add(v);
                    adj.get(v).add(prev);
                }
                prev = v;
            }
        }
        List<double[]> result = new ArrayList<>();
        if (vertices.isEmpty()) return result;

        // 3. Убираем «хвосты» — цепочки, не входящие ни в один цикл (вершины степени ≤ 1, повторно).
        boolean[] removed = new boolean[vertices.size()];
        ArrayDeque<Integer> leaves = new ArrayDeque<>();
        for (int i = 0; i < vertices.size(); i++) {
            if (adj.get(i).size() <= 1) leaves.add(i);
        }
        while (!leaves.isEmpty()) {
            int v = leaves.poll();
            if (removed[v]) continue;
            removed[v] = true;
            for (int w : new ArrayList<>(adj.get(v))) {
                adj.get(w).remove(Integer.valueOf(v));
                if (!removed[w] && adj.get(w).size() <= 1) leaves.add(w);
            }
            adj.get(v).clear();
        }

        // 4. Старт — самая левая (затем нижняя) вершина, «пришли» с запада, снаружи графа.
        int start = -1;
        for (int i = 0; i < vertices.size(); i++) {
            if (removed[i]) continue;
            if (start < 0) {
                start = i;
                continue;
            }
            double[] a = vertices.get(i);
            double[] b = vertices.get(start);
            if (a[0] < b[0] || (a[0] == b[0] && a[1] < b[1])) start = i;
        }
        if (start < 0 || adj.get(start).isEmpty()) return result;
        double[] s = vertices.get(start);
        double refAngle = Math.PI; // направление «назад» — на запад
        int current = start;
        int firstNext = -1;
        int limit = 4 * vertices.size() + 16;
        for (int step = 0; step < limit; step++) {
            result.add(vertices.get(current));
            int next = nextClockwise(current, refAngle, vertices, adj);
            if (step == 0) {
                firstNext = next;
            } else if (current == start && next == firstNext) {
                result.remove(result.size() - 1);
                break;
            }
            double[] c = vertices.get(current);
            double[] n = vertices.get(next);
            refAngle = Math.atan2(c[1] - n[1], c[0] - n[0]);
            current = next;
        }
        if (result.isEmpty()) result.add(s);
        return result;
    }

    /** Следующее ребро по часовой стрелке от направления refAngle (направление «откуда пришли»). */
    private static int nextClockwise(int v, double refAngle, List<double[]> vertices, List<List<Integer>> adj) {
        double[] p = vertices.get(v);
        int best = -1;
        double bestDelta = Double.MAX_VALUE;
        for (int w : adj.get(v)) {
            double[] q = vertices.get(w);
            double a = Math.atan2(q[1] - p[1], q[0] - p[0]);
            double delta = refAngle - a;
            while (delta <= 1e-12) delta += 2 * Math.PI;
            while (delta > 2 * Math.PI + 1e-12) delta -= 2 * Math.PI;
            if (delta < bestDelta) {
                bestDelta = delta;
                best = w;
            }
        }
        return best;
    }

    private static int vertex(double x, double z, Map<Long, Integer> index, List<double[]> vertices, List<List<Integer>> adj) {
        long kx = Math.round(x * 1000);
        long kz = Math.round(z * 1000);
        long key = kx * 73856093L ^ kz * 19349663L;
        Integer existing = index.get(key);
        if (existing != null) {
            double[] v = vertices.get(existing);
            if (Math.abs(v[0] - x) < 0.002 && Math.abs(v[1] - z) < 0.002) return existing;
        }
        vertices.add(new double[]{x, z});
        adj.add(new ArrayList<>());
        index.put(key, vertices.size() - 1);
        return vertices.size() - 1;
    }

    /** Параметры {t, u} строгого пересечения двух отрезков (без общих концов) или null. */
    private static double[] intersect(double[] e, double[] f) {
        double rx = e[2] - e[0], rz = e[3] - e[1];
        double sx = f[2] - f[0], sz = f[3] - f[1];
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-12) return null;
        double qpx = f[0] - e[0], qpz = f[1] - e[1];
        double t = (qpx * sz - qpz * sx) / den;
        double u = (qpx * rz - qpz * rx) / den;
        double eps = 1e-6;
        if (t <= eps || t >= 1 - eps || u <= eps || u >= 1 - eps) return null;
        return new double[]{t, u};
    }

    public static double perimeter(List<double[]> poly) {
        double sum = 0;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            sum += Math.hypot(poly.get(i)[0] - poly.get(j)[0], poly.get(i)[1] - poly.get(j)[1]);
        }
        return sum;
    }

    /** Площадь многоугольника (формула Гаусса), без знака. */
    public static double area(List<double[]> poly) {
        double sum = 0;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            sum += (poly.get(j)[0] * poly.get(i)[1]) - (poly.get(i)[0] * poly.get(j)[1]);
        }
        return Math.abs(sum) / 2;
    }
}
