package com.gpsnavigation.route;

import org.bukkit.World;

/**
 * Рельеф для маршрута: где игрок может стоять и можно ли пройти по прямой.
 * <p>
 * Точка «стоять можно» — под ногами твёрдый блок, на уровне ног и головы нет твёрдых блоков.
 * Незагруженные чанки не читаются: там считается, что пройти можно, а высота берётся из ожидаемой.
 */
public final class Terrain {

    /** Насколько можно подняться за шаг в 1 блок (ступенька/блок). */
    public static final int STEP_UP = 1;
    /** Насколько можно спуститься за шаг в 1 блок. */
    public static final int STEP_DOWN = 3;

    private final World world;
    /** Сколько ещё столбцов можно проверить (защита от долгих расчётов на огромных картах). */
    private int budget;

    public Terrain(World world, int budget) {
        this.world = world;
        this.budget = budget;
    }

    public boolean exhausted() {
        return budget <= 0;
    }

    private boolean loaded(int bx, int bz) {
        return world.isChunkLoaded(bx >> 4, bz >> 4);
    }

    private boolean solid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    /** Можно ли стоять на высоте y (ноги) в столбце bx, bz. */
    public boolean standable(int bx, int y, int bz) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 2) return false;
        return solid(bx, y - 1, bz) && !solid(bx, y, bz) && !solid(bx, y + 1, bz);
    }

    /**
     * Ближайшая к ожидаемой высоте точка, где можно стоять, в пределах [guess - down, guess + up].
     * NaN — подходящей точки нет; если чанк не загружен — сама ожидаемая высота.
     */
    public double snap(double x, double z, double guess, int down, int up) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!loaded(bx, bz)) return guess;
        budget--;
        int g = (int) Math.round(guess);
        int max = Math.max(down, up);
        for (int d = 0; d <= max; d++) {
            if (d <= up && standable(bx, g + d, bz)) return g + d;
            if (d > 0 && d <= down && standable(bx, g - d, bz)) return g - d;
        }
        return Double.NaN;
    }

    /**
     * Земля под/над точкой маршрута, с приоритетом поверхности сверху:
     * <ul>
     *   <li>на этой высоте можно стоять — она и есть;</li>
     *   <li>точка внутри блоков (маршрут прошёл сквозь холм) — вверх до 3 блоков, затем вниз до 2
     *       (потолок туннеля), затем вверх до 16 — на поверхность холма; пещеры снизу не берутся;</li>
     *   <li>точка в воздухе — вниз до 2 блоков на землю; дальше земли нет — NaN (мост, лестница: точка остаётся в воздухе).</li>
     * </ul>
     */
    public double ground(double x, double z, double guess) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!loaded(bx, bz)) return guess;
        budget--;
        int g = (int) Math.floor(guess + 0.01);
        if (standable(bx, g, bz)) return g;
        if (solid(bx, g, bz)) {
            for (int d = 1; d <= 3; d++) if (standable(bx, g + d, bz)) return g + d;
            for (int d = 1; d <= 2; d++) if (standable(bx, g - d, bz)) return g - d;
            for (int d = 4; d <= 16; d++) if (standable(bx, g + d, bz)) return g + d;
            return Double.NaN;
        }
        for (int d = 1; d <= 2; d++) if (standable(bx, g - d, bz)) return g - d;
        return Double.NaN;
    }

    /** Верх коллизии блока (абсолютная высота); для блоков без коллизии — низ блока. */
    private double top(int x, int y, int z) {
        org.bukkit.block.Block b = world.getBlockAt(x, y, z);
        if (b.getType().isAir() || b.isPassable()) return y;
        org.bukkit.util.BoundingBox box = b.getBoundingBox();
        return box.getHeight() <= 0 ? y : box.getMaxY();
    }

    /**
     * Настоящая высота поверхности, на которой стоит игрок с ногами на уровне y:
     * верх блока под ногами (полублок — +0.5, ковёр — +0.06) или низкий блок с коллизией в самом уровне ног.
     */
    public double surface(int bx, int y, int bz) {
        double below = top(bx, y - 1, bz);
        org.bukkit.block.Block feet = world.getBlockAt(bx, y, bz);
        // В уровне ног учитывается только низкий блок с коллизией (ковёр, слой снега); воздух и трава — нет.
        if (feet.getType().isAir() || feet.isPassable()) return below;
        return Math.max(below, top(bx, y, bz));
    }

    /**
     * Где ставить указатель для точки маршрута (возвращает точку поверхности, над которой он висит; никогда не null):
     * <ol>
     *   <li>в этом столбце есть земля ({@link #ground}: внутри холма — его поверхность сверху) — над ней (с учётом полублоков, ковров);</li>
     *   <li>точка внутри блоков — ближайшее место рядом (до 3, затем до 6 блоков), где можно стоять, иначе ближайший воздух;</li>
     *   <li>точка в воздухе (мост, лестница, ручная связь) — сама точка;</li>
     *   <li>свободного места рядом нет совсем — тоже сама точка.</li>
     * </ol>
     */
    public org.bukkit.util.Vector pointerSpot(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!loaded(bx, bz)) return new org.bukkit.util.Vector(x, y, z);
        double sy = ground(x, z, y);
        if (!Double.isNaN(sy)) return new org.bukkit.util.Vector(x, surface(bx, (int) sy, bz), z);
        int by = (int) Math.floor(y);
        if (!solid(bx, by, bz) && !solid(bx, by + 1, bz)) return new org.bukkit.util.Vector(x, y, z);

        // Внутри блоков: ищем ближайшее место вокруг — сначала где можно стоять, потом просто воздух
        // (сначала в 3 блоках, если пусто — в 6).
        for (int R : new int[]{3, 6}) {
            org.bukkit.util.Vector found = nearestFree(bx, by, bz, R);
            if (found != null) return found;
        }
        return new org.bukkit.util.Vector(x, y, z);
    }

    /**
     * Отодвигает центр указателя от соседних твёрдых блоков (стенка ступеньки, склон, угол), чтобы шар
     * радиусом ~margin не залезал в них: по каждой оси и по диагонали центр не ближе margin к стенке.
     */
    public org.bukkit.util.Vector awayFromWalls(org.bukkit.util.Vector p, double margin) {
        int bx = (int) Math.floor(p.getX()), bz = (int) Math.floor(p.getZ());
        if (!loaded(bx, bz)) return p;
        double fx = p.getX() - bx, fz = p.getZ() - bz;
        double lo = margin, hi = 1 - margin;
        // Проверяем блоки на уровне нижней и верхней точки шара.
        int y1 = (int) Math.floor(p.getY() - margin), y2 = (int) Math.floor(p.getY() + margin);
        boolean wxm = wall(bx - 1, y1, y2, bz), wxp = wall(bx + 1, y1, y2, bz);
        boolean wzm = wall(bx, y1, y2, bz - 1), wzp = wall(bx, y1, y2, bz + 1);
        if (wxm && fx < lo) fx = lo;
        if (wxp && fx > hi) fx = hi;
        if (wzm && fz < lo) fz = lo;
        if (wzp && fz > hi) fz = hi;
        // Углы: сосед по диагонали твёрдый, а центр в этом углу клетки — отодвигаем по обеим осям.
        int sx = fx < lo ? -1 : fx > hi ? 1 : 0, sz = fz < lo ? -1 : fz > hi ? 1 : 0;
        if (sx != 0 && sz != 0 && wall(bx + sx, y1, y2, bz + sz)) {
            fx = sx < 0 ? lo : hi;
            fz = sz < 0 ? lo : hi;
        }
        return new org.bukkit.util.Vector(bx + fx, p.getY(), bz + fz);
    }

    private boolean wall(int x, int y1, int y2, int z) {
        if (!loaded(x, z)) return false;
        for (int y = y1; y <= y2; y++) {
            if (solid(x, y, z)) return true;
        }
        return false;
    }

    private org.bukkit.util.Vector nearestFree(int bx, int by, int bz, int R) {
        int[] stand = null, air = null;
        double bestStand = Double.MAX_VALUE, bestAir = Double.MAX_VALUE;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (!loaded(bx + dx, bz + dz)) continue;
                for (int dy = -R; dy <= R; dy++) {
                    int cx = bx + dx, cy = by + dy, cz = bz + dz;
                    if (cy <= world.getMinHeight() || cy >= world.getMaxHeight() - 2) continue;
                    if (solid(cx, cy, cz) || solid(cx, cy + 1, cz)) continue;
                    // Вниз (в пещеры под поверхностью) — в 4 раза «дальше», чем вверх и в стороны.
                    double d = dx * dx + dz * dz + (dy < 0 ? 4.0 * dy * dy : dy * dy);
                    if (solid(cx, cy - 1, cz)) {
                        if (d < bestStand) { bestStand = d; stand = new int[]{cx, cy, cz}; }
                    } else if (d < bestAir) {
                        bestAir = d; air = new int[]{cx, cy, cz};
                    }
                }
            }
        }
        if (stand != null) return new org.bukkit.util.Vector(stand[0] + 0.5, surface(stand[0], stand[1], stand[2]), stand[2] + 0.5);
        if (air != null) return new org.bukkit.util.Vector(air[0] + 0.5, air[1], air[2] + 0.5);
        return null;
    }

    /**
     * Идёт по прямой от (ax, ay, az) к (bx, bz) шагами по 1 блоку, как игрок: на каждом шаге можно подняться
     * не больше чем на {@link #STEP_UP} и спуститься не больше чем на {@link #STEP_DOWN}.
     * Возвращает высоту в конечной точке или NaN, если путь упирается в стену/обрыв.
     */
    public double walk(double ax, double ay, double az, double bx, double bz) {
        double len = Math.hypot(bx - ax, bz - az);
        int steps = (int) Math.ceil(len);
        double y = ay;
        for (int i = 1; i <= steps; i++) {
            if (budget <= 0) return y;
            double t = (double) i / steps;
            double ny = snap(ax + (bx - ax) * t, az + (bz - az) * t, y, STEP_DOWN, STEP_UP);
            if (Double.isNaN(ny)) return Double.NaN;
            y = ny;
        }
        return y;
    }
}
