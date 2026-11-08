package com.gpsnavigation.route;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.Settings;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.MarkerType;
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
        List<Vector> pts = new ArrayList<>();
        pts.add(loc.toVector());
        // Бездорожье в обход мёртвых зон: точки ставятся на землю, высота плавно идёт к первому маркеру.
        RoadMarker first = path.get(0);
        double total = 0;
        double px = loc.getX(), pz = loc.getZ();
        for (double[] w : result.offroad) {
            total += Math.hypot(w[0] - px, w[1] - pz);
            px = w[0];
            pz = w[1];
        }
        total += Math.hypot(first.cx() - px, first.cz() - pz);
        double walked = 0;
        px = loc.getX();
        pz = loc.getZ();
        for (double[] w : result.offroad) {
            walked += Math.hypot(w[0] - px, w[1] - pz);
            px = w[0];
            pz = w[1];
            double guess = loc.getY() + (first.y() - loc.getY()) * (total <= 0 ? 0 : walked / total);
            addPoint(pts, new Vector(w[0], groundY(player.getWorld(), w[0], w[1], guess), w[1]));
        }
        for (RoadMarker m : path) addPoint(pts, new Vector(m.cx(), m.y(), m.cz()));
        addPoint(pts, new Vector(npc.x(), npc.y(), npc.z()));
        clearVisuals(route);
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
        com.gpsnavigation.path.RoutePlanner planner = new com.gpsnavigation.path.RoutePlanner(
                plugin.network(), plugin.zones(), route.mapId, route.world,
                loc.getX(), loc.getY() + 0.5, loc.getZ(), s.startSearchRadius, s.offroadPenalty,
                m -> canSee(player, m) ? 1.0 : 1.5);
        return planner.find(goal);
    }

    /** Высота земли рядом с ожидаемой: первый блок с опорой и двумя блоками воздуха над ним. */
    private double groundY(World w, double x, double z, double guess) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return guess;
        int g = (int) Math.floor(guess);
        for (int d = 0; d <= 8; d++) {
            for (int sign : new int[]{1, -1}) {
                int yy = g + d * sign;
                if (yy <= w.getMinHeight() || yy >= w.getMaxHeight() - 2) continue;
                if (w.getBlockAt(bx, yy - 1, bz).getType().isSolid()
                        && !w.getBlockAt(bx, yy, bz).getType().isSolid()
                        && !w.getBlockAt(bx, yy + 1, bz).getType().isSolid()) return yy;
                if (d == 0) break;
            }
        }
        return guess;
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

        // Вход в здание: отдельный светящийся указатель у ближайшего входа впереди.
        int entranceKey = Integer.MIN_VALUE;
        double entranceArc = -1;
        for (int i = 0; i < r.markers.size(); i++) {
            RoadMarker m = r.markers.get(i);
            if (m.type() != MarkerType.ENTRANCE) continue;
            double arc = r.project(new Vector(m.cx(), m.y(), m.cz()))[0];
            if (arc > r.progress + 0.5 && arc - r.progress <= s.pointerMaxDistance * 2) {
                entranceKey = -1 - i;
                entranceArc = arc;
                break;
            }
        }

        Iterator<Map.Entry<Integer, PointerHandle>> it = r.pointers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PointerHandle> e = it.next();
            int k = e.getKey();
            boolean keep = k >= 0 ? (k >= kStart && k <= kEnd) : k == entranceKey;
            if (!keep || !e.getValue().isValid()) {
                e.getValue().remove();
                it.remove();
            }
        }
        for (int k = Math.max(1, kStart); k <= kEnd; k++) {
            if (r.pointers.containsKey(k)) continue;
            Vector v = r.pointAt(k * sp);
            if (v == null) continue;
            boolean nearEntrance = entranceArc >= 0 && Math.abs(k * sp - entranceArc) < sp / 2;
            Vector next = r.pointAt(Math.min((k + 1) * sp, total));
            if (next != null) next.setY(next.getY() + s.pointerHeight);
            PointerHandle e = plugin.visuals().spawnPointer(player,
                    new Location(world, v.getX(), v.getY() + s.pointerHeight, v.getZ()), nearEntrance, next);
            if (e != null) r.pointers.put(k, e);
        }
        if (entranceKey != Integer.MIN_VALUE && !r.pointers.containsKey(entranceKey)) {
            Vector v = r.pointAt(entranceArc);
            if (v != null) {
                PointerHandle e = plugin.visuals().spawnPointer(player,
                        new Location(world, v.getX(), v.getY() + s.pointerHeight + 0.5, v.getZ()), true);
                if (e != null) r.pointers.put(entranceKey, e);
            }
        }
    }

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
        if (r.npcMarker != null) {
            r.npcMarker.forget();
            r.npcMarker = null;
        }
        r.glowEntity = null;
        r.lastEntityLookup = 0;
    }

    private void clearVisuals(ActiveRoute r) {
        for (PointerHandle h : r.pointers.values()) h.remove();
        r.pointers.clear();
        stopNpcHighlight(Bukkit.getPlayer(r.playerId), r);
    }
}
