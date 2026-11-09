package com.gpsnavigation.route;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.Settings;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Управляет активными маршрутами игроков.
 * Указатели стоят каждые pointer-spacing блоков маршрута и не дальше pointer-max-distance впереди игрока;
 * пройденные указатели исчезают, при отклонении от маршрута он пересчитывается.
 */
public final class RouteManager {

    private final GPSNavigation plugin;
    private final Map<UUID, ActiveRoute> routes = new HashMap<>();

    public RouteManager(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    public ActiveRoute get(UUID player) {
        return routes.get(player);
    }

    public boolean isRouteTo(Player player, String npcId) {
        ActiveRoute r = routes.get(player.getUniqueId());
        return r != null && r.npcId.equals(npcId);
    }

    public int activeCount() {
        return routes.size();
    }

    // ---------- Запуск / остановка ----------

    public boolean start(Player player, GpsNpc npc) {
        TerrainMap map = plugin.maps().get(npc.mapId());
        if (map == null) {
            Msg.send(player, "<red>Карта NPC не найдена.");
            return false;
        }
        if (!player.getWorld().getName().equals(npc.world())) {
            Msg.send(player, "<red>Этот NPC находится в другом мире (<white>" + Msg.esc(npc.world()) + "</white>).");
            return false;
        }
        ActiveRoute previous = routes.remove(player.getUniqueId());
        if (previous != null) clearVisuals(previous);

        ActiveRoute route = new ActiveRoute(player.getUniqueId(), npc.id(), npc.mapId(), npc.world());
        String error = compute(player, route);
        if (error != null) {
            Msg.send(player, "<red>" + error);
            return false;
        }
        routes.put(player.getUniqueId(), route);
        Msg.send(player, "<green>Маршрут к <white>" + Msg.esc(npc.displayName()) + "</white> построен: <yellow>"
                + Math.round(route.totalLength()) + " м</yellow>, маркеров: <yellow>" + route.markers.size()
                + "</yellow>. <gray>Следуйте за указателями.");
        tickRoute(player, route);
        return true;
    }

    public void stop(UUID playerId, boolean notify) {
        ActiveRoute r = routes.remove(playerId);
        if (r == null) return;
        clearVisuals(r);
        if (notify) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) Msg.send(p, "<yellow>GPS остановлен.");
        }
    }

    public void stopAll() {
        for (ActiveRoute r : routes.values()) clearVisuals(r);
        routes.clear();
    }

    /** Дорожная сеть или зоны карты изменились — маршруты будут пересчитаны на следующем тике. */
    public void onNetworkChanged(String mapId) {
        for (ActiveRoute r : routes.values()) {
            if (r.mapId.equals(mapId)) r.needsRecalc = true;
        }
    }

    public void onNpcRemoved(GpsNpc npc) {
        for (UUID id : new ArrayList<>(routes.keySet())) {
            ActiveRoute r = routes.get(id);
            if (r.npcId.equals(npc.id())) {
                stop(id, false);
                Player p = Bukkit.getPlayer(id);
                if (p != null) Msg.send(p, "<yellow>Цель маршрута удалена, GPS остановлен.");
            }
        }
    }

    public void onMapRemoved(String mapId) {
        for (UUID id : new ArrayList<>(routes.keySet())) {
            if (routes.get(id).mapId.equals(mapId)) {
                stop(id, false);
                Player p = Bukkit.getPlayer(id);
                if (p != null) Msg.send(p, "<yellow>Карта маршрута удалена, GPS остановлен.");
            }
        }
    }

    // ---------- Расчёт ----------

    /** Строит маршрут. Возвращает текст ошибки или null. */
    private String compute(Player player, ActiveRoute route) {
        GpsNpc npc = plugin.npcs().get(route.npcId);
        if (npc == null) return "NPC больше не существует.";
        RoadMarker goal = plugin.network().get(npc.linkedMarker());
        if (goal == null || plugin.zones().isBlocked(goal)) {
            goal = plugin.roads().relinkNpc(npc);
        }
        if (goal == null) return "NPC «" + Msg.esc(npc.displayName()) + "» не привязан к дорожной сети — маршрут построить нельзя.";
        Location loc = player.getLocation();
        Settings s = plugin.settings();
        com.gpsnavigation.path.RoutePlanner.Result result = findPath(player, route, goal);
        if (result == null || result.markers.isEmpty()) {
            return "Маршрут не найден: дорога к NPC отсутствует или перекрыта мёртвой зоной.";
        }
        List<RoadMarker> path = result.markers;
        World world = player.getWorld();
        Terrain terrain = new Terrain(world, POINT_BUDGET);
        double startY = startY(terrain, loc);

        // Опорные точки: игрок → обход мёртвых зон → маркеры → NPC. Между ними маршрут прорисовывается
        // по земле через каждый блок, чтобы указатели не висели в воздухе и не уходили в холмы и стены.
        List<Vector> pts = new ArrayList<>();
        Vector cur = new Vector(loc.getX(), startY, loc.getZ());
        pts.add(cur);
        for (double[] w : result.offroad) {
            cur = offroadLeg(terrain, pts, cur, w[0], w[1]);
        }
        RoadMarker first = path.get(0);
        offroadLeg(terrain, pts, cur, first.cx(), first.cz());
        addPoint(pts, new Vector(first.cx(), first.y(), first.cz()));
        for (int i = 1; i < path.size(); i++) {
            RoadMarker m = path.get(i);
            roadLeg(terrain, pts, new Vector(m.cx(), m.y(), m.cz()));
        }
        roadLeg(terrain, pts, new Vector(npc.x(), npc.y(), npc.z()));
        releasePointers(route);
        route.markers = path;
        route.setPoints(pts);
        route.progress = 0;
        route.lastRecalc = System.currentTimeMillis();
        route.needsRecalc = false;
        route.lastPosition = null;
        return null;
    }

    /**
     * Единый A* от игрока до NPC: по бездорожью в обход мёртвых зон (через узлы вокруг их контуров)
     * до входа на дорогу и дальше по дорожной сети. Прямой выход к маркеру, закрытому рельефом, дороже в 1.5 раза.
     */
    private com.gpsnavigation.path.RoutePlanner.Result findPath(Player player, ActiveRoute route, RoadMarker goal) {
        Settings s = plugin.settings();
        Location loc = player.getLocation();
        Terrain terrain = new Terrain(player.getWorld(), WALK_BUDGET);
        double startY = startY(terrain, loc);
        com.gpsnavigation.path.RoutePlanner.Result result = new com.gpsnavigation.path.RoutePlanner(
                plugin.network(), plugin.zones(), route.mapId, route.world,
                loc.getX(), loc.getY() + 0.5, loc.getZ(), s.startSearchRadius, s.offroadPenalty,
                m -> canSee(player, m) ? 1.0 : 1.5, startY, terrain::walk).find(goal);
        if (result != null) return result;
        // Ногами по прямой не выйти ни к одному маркеру (например, игрок в закрытой постройке) —
        // строим как раньше, без проверки рельефа, чтобы GPS всё равно показал направление.
        return new com.gpsnavigation.path.RoutePlanner(
                plugin.network(), plugin.zones(), route.mapId, route.world,
                loc.getX(), loc.getY() + 0.5, loc.getZ(), s.startSearchRadius, s.offroadPenalty,
                m -> canSee(player, m) ? 1.0 : 1.5, startY, null).find(goal);
    }

    /** Сколько столбцов блоков можно прочитать при проверке проходимости в A* и при прорисовке точек. */
    private static final int WALK_BUDGET = 60_000;
    private static final int POINT_BUDGET = 20_000;

    /** Высота ног игрока на земле: если он в воздухе или в воде — ближайшая земля под ним. */
    private static double startY(Terrain t, Location loc) {
        double y = t.snap(loc.getX(), loc.getZ(), Math.floor(loc.getY() + 0.01), 16, 1);
        return Double.isNaN(y) ? loc.getY() : y;
    }

    /** Участок по бездорожью: шаги по 1 блоку по земле (подъём до 1, спуск до 3 блоков за шаг). */
    private static Vector offroadLeg(Terrain t, List<Vector> pts, Vector from, double bx, double bz) {
        double len = Math.hypot(bx - from.getX(), bz - from.getZ());
        int steps = (int) Math.ceil(len);
        double y = from.getY();
        Vector p = from;
        for (int i = 1; i <= steps; i++) {
            double k = (double) i / steps;
            double x = from.getX() + (bx - from.getX()) * k;
            double z = from.getZ() + (bz - from.getZ()) * k;
            double ny = t.snap(x, z, y, Terrain.STEP_DOWN, Terrain.STEP_UP);
            if (Double.isNaN(ny)) ny = t.ground(x, z, y);
            if (!Double.isNaN(ny)) y = ny;
            p = new Vector(x, y, z);
            addPoint(pts, p);
        }
        return p;
    }

    /**
     * Участок дороги между маркерами: прямая между ними, но каждая точка ставится на землю ({@link Terrain#ground}):
     * сквозь холм — на его поверхность, над низиной — вниз до 2 блоков; мосты и лестницы остаются на месте.
     */
    private static void roadLeg(Terrain t, List<Vector> pts, Vector to) {
        Vector from = pts.get(pts.size() - 1);
        double len = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int steps = (int) Math.ceil(len);
        for (int i = 1; i < steps; i++) {
            double k = (double) i / steps;
            double x = from.getX() + (to.getX() - from.getX()) * k;
            double z = from.getZ() + (to.getZ() - from.getZ()) * k;
            double guess = from.getY() + (to.getY() - from.getY()) * k;
            double y = t.ground(x, z, guess);
            addPoint(pts, new Vector(x, Double.isNaN(y) ? guess : y, z));
        }
        addPoint(pts, to);
    }

    /** Видит ли игрок маркер (нет твёрдых блоков между глазами и точкой над маркером). */
    private boolean canSee(Player player, RoadMarker m) {
        Location eye = player.getEyeLocation();
        Vector dir = new Vector(m.cx() - eye.getX(), m.y() + 1.2 - eye.getY(), m.cz() - eye.getZ());
        double dist = dir.length();
        if (dist < 1) return true;
        World w = player.getWorld();
        if (!w.isChunkLoaded(m.x() >> 4, m.z() >> 4)) return true;
        return w.rayTraceBlocks(eye, dir.normalize(), dist, org.bukkit.FluidCollisionMode.NEVER, true) == null;
    }

    private static void addPoint(List<Vector> pts, Vector v) {
        if (pts.isEmpty() || pts.get(pts.size() - 1).distanceSquared(v) > 0.0001) pts.add(v);
    }

    private boolean recalc(Player player, ActiveRoute route) {
        String error = compute(player, route);
        if (error != null) {
            routes.remove(route.playerId);
            clearVisuals(route);
            Msg.send(player, "<red>" + error + " GPS остановлен.");
            return false;
        }
        return true;
    }

    // ---------- Обновление ----------

    public void tick() {
        if (routes.isEmpty()) return;
        for (ActiveRoute r : new ArrayList<>(routes.values())) {
            Player p = Bukkit.getPlayer(r.playerId);
            if (p == null || !p.isOnline()) {
                stop(r.playerId, false);
                continue;
            }
            tickRoute(p, r);
        }
    }

    private void tickRoute(Player player, ActiveRoute r) {
        Settings s = plugin.settings();
        GpsNpc npc = plugin.npcs().get(r.npcId);
        if (npc == null) {
            stop(r.playerId, false);
            Msg.send(player, "<yellow>Цель маршрута удалена, GPS остановлен.");
            return;
        }
        if (!player.getWorld().getName().equals(r.world)) {
            clearVisuals(r);
            if (!r.warnedWorld) {
                Msg.send(player, "<yellow>Маршрут приостановлен: вернитесь в мир <white>" + Msg.esc(r.world) + "</white>.");
                r.warnedWorld = true;
            }
            return;
        }
        if (r.warnedWorld) {
            r.warnedWorld = false;
            r.needsRecalc = true;
        }
        if (r.needsRecalc && !recalc(player, r)) return;

        Vector pos = player.getLocation().toVector();
        Vector npcPos = new Vector(npc.x(), npc.y(), npc.z());
        double toNpc = pos.distance(npcPos);
        if (toNpc <= s.arriveDistance) {
            stop(r.playerId, false);
            DisplayLibVisuals dl = plugin.displayLib();
            if (dl != null && dl.ready()) dl.arrive(player, new Location(player.getWorld(), npc.x(), npc.y() + 1.2, npc.z()));
            Msg.send(player, "<green>Вы прибыли к <white>" + Msg.esc(npc.displayName()) + "</white>!");
            return;
        }

        boolean moved = r.lastPosition == null || r.lastPosition.distanceSquared(pos) > 0.09;
        if (moved) {
            double[] pr = r.project(pos);
            r.progress = pr[0];
            r.lastPosition = pos;
            if (pr[1] > s.recalcDistance && System.currentTimeMillis() - r.lastRecalc >= s.recalcCooldownMs) {
                if (!recalc(player, r)) return;
                r.progress = 0;
                r.lastPosition = pos;
            }
        }
        updatePointers(player, r);
        updateNpcHighlight(player, r, npc, toNpc);
        Msg.actionBar(player, "<green>GPS</green> <gray>→</gray> <white>" + Msg.esc(npc.displayName())
                + "</white> <gray>|</gray> <yellow>" + Math.round(r.remaining()) + " м</yellow>");
    }

    private void updatePointers(Player player, ActiveRoute r) {
        Settings s = plugin.settings();
        World world = player.getWorld();
        double sp = s.pointerSpacing;
        double total = r.totalLength();
        int kStart = (int) Math.floor(r.progress / sp) + 1;
        int kEnd = (int) Math.floor(Math.min(r.progress + s.pointerMaxDistance, total) / sp);

        Iterator<Map.Entry<Integer, PointerHandle>> it = r.pointers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PointerHandle> e = it.next();
            int k = e.getKey();
            boolean keep = k >= kStart && k <= kEnd;
            if (!keep || !e.getValue().isValid()) {
                e.getValue().remove();
                r.pointerPos.remove(k);
                it.remove();
            }
        }
        Terrain terrain = new Terrain(world, 4096);
        for (int k = Math.max(1, kStart); k <= kEnd; k++) {
            if (r.pointers.containsKey(k)) continue;
            Vector v = r.pointAt(k * sp);
            if (v == null) continue;
            // Указатель — на pointer.height над настоящей поверхностью (полублок, ступенька, ковёр);
            // если точка маршрута внутри блоков — переносится в ближайшее свободное место рядом.
            Vector spot = pointerAt(terrain, v, s.pointerHeight);
            Vector next = r.pointAt(Math.min((k + 1) * sp, total));
            if (next != null) next = pointerAt(terrain, next, s.pointerHeight);
            PointerHandle e = takeSpare(r, spot);
            if (e == null) {
                e = plugin.visuals().spawnPointer(player,
                        new Location(world, spot.getX(), spot.getY(), spot.getZ()), false, next);
            }
            if (e != null) {
                r.pointers.put(k, e);
                r.pointerPos.put(k, spot);
            }
        }
        // Указатели прошлого расчёта, которым не нашлось места в новом маршруте.
        for (ActiveRoute.Spare sp2 : r.spare) sp2.handle().remove();
        r.spare.clear();
    }

    /** Центр указателя для точки маршрута: над поверхностью, вне блоков и не впритык к стенкам. */
    private static Vector pointerAt(Terrain t, Vector routePoint, double height) {
        Vector spot = t.pointerSpot(routePoint.getX(), routePoint.getY(), routePoint.getZ());
        spot.setY(spot.getY() + height);
        return t.awayFromWalls(spot, POINTER_RADIUS);
    }

    /** Примерный радиус указателя (шар Ока Края / снаряд шалкера) — на столько он держится от стенок. */
    private static final double POINTER_RADIUS = 0.35;

    /**
     * Подсветка цели эффектом свечения (контур), а не частицами — видна даже с отключёнными частицами.
     * Свечение сущности NPC отправляется пакетом только этому игроку; остальные его не видят.
     * Сервер может перезаписать флаги сущности, поэтому пакет повторяется при каждом обновлении маршрута.
     * Если сущность NPC не найдена, над точкой NPC висит светящийся указатель.
     */
    private void updateNpcHighlight(Player player, ActiveRoute r, GpsNpc npc, double distance) {
        Settings s = plugin.settings();
        if (!s.npcGlow || distance > s.npcHighlightDistance) {
            stopNpcHighlight(player, r);
            return;
        }
        Entity target = plugin.packets().isAvailable() ? findNpcEntity(npc, r) : null;
        DisplayLibVisuals dl = plugin.displayLib();
        if (dl != null && dl.ready()) {
            // DisplayLib: золотая спираль вокруг NPC всегда + свечение самой сущности, если она найдена.
            if (target != null) plugin.packets().sendFlags(player, target, true);
            if (r.npcMarker == null || !r.npcMarker.isValid()) {
                r.npcMarker = dl.target(player, new Location(player.getWorld(), npc.x(), npc.y() + 0.1, npc.z()));
            }
            return;
        }
        if (target != null) {
            if (r.npcMarker != null) {
                r.npcMarker.remove();
                r.npcMarker = null;
            }
            plugin.packets().sendFlags(player, target, true);
            return;
        }
        if (r.npcMarker == null || !r.npcMarker.isValid()) {
            r.npcMarker = plugin.visuals().spawnPointer(player,
                    new Location(player.getWorld(), npc.x(), npc.y() + 2.3, npc.z()), true);
        }
    }

    private void stopNpcHighlight(Player player, ActiveRoute r) {
        if (r.glowEntity != null) {
            Entity e = Bukkit.getEntity(r.glowEntity);
            if (e != null && player != null) plugin.packets().sendFlags(player, e, false);
            r.glowEntity = null;
        }
        if (r.npcMarker != null) {
            r.npcMarker.remove();
            r.npcMarker = null;
        }
    }

    /**
     * Сущность NPC: привязанная командой /gpsnpc bind, иначе ближайшая живая сущность (или NPC Citizens)
     * в 1.5 блоках от точки NPC. Поиск выполняется не чаще раза в 2 секунды.
     */
    private Entity findNpcEntity(GpsNpc npc, ActiveRoute r) {
        if (npc.entityUuid() != null) {
            Entity bound = Bukkit.getEntity(npc.entityUuid());
            if (bound != null && bound.isValid()) {
                r.glowEntity = bound.getUniqueId();
                return bound;
            }
        }
        World w = Bukkit.getWorld(npc.world());
        if (w == null) return null;
        Location npcLoc = npc.location(w);
        if (r.glowEntity != null) {
            Entity e = Bukkit.getEntity(r.glowEntity);
            if (e != null && e.isValid() && e.getWorld().equals(w) && e.getLocation().distanceSquared(npcLoc) <= 9) return e;
            r.glowEntity = null;
        }
        long now = System.currentTimeMillis();
        if (now - r.lastEntityLookup < 2000) return null;
        r.lastEntityLookup = now;
        if (!w.isChunkLoaded(npcLoc.getBlockX() >> 4, npcLoc.getBlockZ() >> 4)) return null;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.getNearbyEntities(npcLoc, 1.5, 2.5, 1.5)) {
            if (plugin.visuals().isVisual(e)) continue;
            boolean citizens = e.hasMetadata("NPC");
            if (e instanceof Player && !citizens) continue;
            if (!(e instanceof LivingEntity) && !citizens) continue;
            double d = e.getLocation().distanceSquared(npcLoc);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best != null) r.glowEntity = best.getUniqueId();
        return best;
    }

    /** Клиент сам удалил все сущности (смерть/возрождение, смена мира) — указатели нужно создать заново. */
    public void resetClient(Player player) {
        ActiveRoute r = routes.get(player.getUniqueId());
        if (r == null) return;
        for (PointerHandle h : r.pointers.values()) h.forget();
        r.pointers.clear();
        r.pointerPos.clear();
        for (ActiveRoute.Spare sp : r.spare) sp.handle().forget();
        r.spare.clear();
        if (r.npcMarker != null) {
            r.npcMarker.forget();
            r.npcMarker = null;
        }
        r.glowEntity = null;
        r.lastEntityLookup = 0;
    }

    /** Перед пересчётом: живые указатели откладываются, чтобы новый маршрут мог забрать совпадающие. */
    private static void releasePointers(ActiveRoute r) {
        for (ActiveRoute.Spare sp : r.spare) sp.handle().remove();
        r.spare.clear();
        for (Map.Entry<Integer, PointerHandle> e : r.pointers.entrySet()) {
            Vector pos = r.pointerPos.get(e.getKey());
            if (pos != null && e.getValue().isValid()) r.spare.add(new ActiveRoute.Spare(pos, e.getValue()));
            else e.getValue().remove();
        }
        r.pointers.clear();
        r.pointerPos.clear();
    }

    /** Отложенный указатель не дальше 0.6 блока от нужного места (или null). */
    private static PointerHandle takeSpare(ActiveRoute r, Vector spot) {
        int best = -1;
        double bestD = 0.36;
        for (int i = 0; i < r.spare.size(); i++) {
            double d = r.spare.get(i).pos().distanceSquared(spot);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return null;
        PointerHandle h = r.spare.remove(best).handle();
        return h.isValid() ? h : null;
    }

    private void clearVisuals(ActiveRoute r) {
        for (PointerHandle h : r.pointers.values()) h.remove();
        r.pointers.clear();
        r.pointerPos.clear();
        for (ActiveRoute.Spare sp : r.spare) sp.handle().remove();
        r.spare.clear();
        stopNpcHighlight(Bukkit.getPlayer(r.playerId), r);
    }
}
