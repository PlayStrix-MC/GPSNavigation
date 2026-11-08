package com.gpsnavigation.core;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.RoadMarker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Бизнес-логика дорожной сети: создание/удаление маркеров, проверка и создание связей,
 * привязка NPC и уведомление активных маршрутов об изменениях.
 */
public final class RoadService {

    public enum LinkCheck {
        OK("связь допустима"),
        SAME("это один и тот же маркер"),
        DIFFERENT_MAP("маркеры принадлежат разным картам"),
        TOO_FAR("расстояние больше радиуса соединения"),
        TOO_HIGH("слишком большой перепад высоты (соедините вручную: Shift+ПКМ или /road link)"),
        BLOCKS("между маркерами есть блоки"),
        ZONE("связь проходит через мёртвую зону"),
        ALREADY("маркеры уже связаны");

        public final String text;

        LinkCheck(String text) {
            this.text = text;
        }
    }

    public static final class CreateResult {
        public RoadMarker marker;
        public RoadMarker existing;
        public DeadZone zone;
        public final List<RoadMarker> linked = new ArrayList<>();
        public final List<String> rejected = new ArrayList<>();
        public final List<GpsNpc> npcsLinked = new ArrayList<>();
    }

    public static final class DeleteResult {
        public final List<GpsNpc> relinked = new ArrayList<>();
        public final List<GpsNpc> orphaned = new ArrayList<>();
        public int formerNeighbors;
    }

    private final GPSNavigation plugin;

    public RoadService(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    // ---------- Проверка связей ----------

    /**
     * Проверка автоматической связи.
     * Радиус считается по горизонтали, перепад высоты ограничен road.max-height-difference,
     * поэтому маркеры на склонах, лестницах и холмах соединяются нормально.
     */
    public LinkCheck check(RoadMarker a, RoadMarker b) {
        return check(a, b, false);
    }

    /**
     * @param manual ручная связь администратора: без ограничения перепада высоты и проверки блоков
     *               (мосты, лестницы, этажи), радиус — road.manual-link-radius.
     */
    public LinkCheck check(RoadMarker a, RoadMarker b, boolean manual) {
        if (a == b) return LinkCheck.SAME;
        if (!a.mapId().equals(b.mapId()) || !a.world().equals(b.world())) return LinkCheck.DIFFERENT_MAP;
        if (manual) {
            if (a.distance(b) > plugin.settings().manualLinkRadius + 1e-6) return LinkCheck.TOO_FAR;
        } else {
            if (a.horizontalDistance(b) > plugin.settings().linkRadius + 1e-6) return LinkCheck.TOO_FAR;
            if (Math.abs(a.y() - b.y()) > plugin.settings().maxHeightDifference + 1e-6) return LinkCheck.TOO_HIGH;
        }
        if (plugin.zones().segmentBlocked(a, b)) return LinkCheck.ZONE;
        if (!manual && plugin.settings().lineOfSight && !clearPath(a, b)) return LinkCheck.BLOCKS;
        return LinkCheck.OK;
    }

    /**
     * Нет ли твёрдых блоков между маркерами. Лучи идут на уровне груди и головы от каждого маркера,
     * поэтому ступени склона/лестницы под линией не мешают, а стены — мешают.
     */
    public boolean clearPath(RoadMarker a, RoadMarker b) {
        World w = Bukkit.getWorld(a.world());
        if (w == null) return true;
        if (!w.isChunkLoaded(a.x() >> 4, a.z() >> 4) || !w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) return true;
        for (double h : new double[]{1.2, 1.7}) {
            Vector dir = new Vector(b.cx() - a.cx(), b.y() - a.y(), b.cz() - a.cz());
            double len = dir.length();
            if (len < 1e-6) continue;
            Location from = new Location(w, a.cx(), a.y() + h, a.cz());
            RayTraceResult hit = w.rayTraceBlocks(from, dir.normalize(), len, FluidCollisionMode.NEVER, true);
            if (hit != null) return false;
        }
        return true;
    }

    // ---------- Маркеры ----------

    public CreateResult createMarker(String mapId, World world, int x, int y, int z) {
        CreateResult result = new CreateResult();
        RoadMarker existing = plugin.network().findAt(world.getName(), x, y, z);
        if (existing != null) {
            result.existing = existing;
            return result;
        }
        DeadZone zone = plugin.zones().zoneAt(mapId, x + 0.5, z + 0.5);
        if (zone != null) {
            result.zone = zone;
            return result;
        }
        RoadMarker marker = plugin.network().create(mapId, world.getName(), x, y, z);
        result.marker = marker;
        double radius = plugin.settings().linkRadius;
        for (RoadMarker other : plugin.network().nearbyColumn(world.getName(), marker.cx(), marker.cz(), radius)) {
            if (other == marker || !other.mapId().equals(mapId)) continue;
            LinkCheck c = check(marker, other);
            if (c == LinkCheck.TOO_FAR) continue;
            if (c == LinkCheck.OK) {
                plugin.network().link(marker, other);
                result.linked.add(other);
            } else {
                result.rejected.add(other.shortId() + " (" + c.text + ")");
            }
        }
        // NPC этой карты без привязки получают новый маркер, если он рядом.
        for (GpsNpc npc : plugin.npcs().ofMap(mapId)) {
            if (npc.linkedMarker() == null && npc.world().equals(world.getName())
                    && Math.hypot(marker.cx() - npc.x(), marker.cz() - npc.z()) <= plugin.settings().npcLinkRadius
                    && Math.abs(marker.y() - npc.y()) <= plugin.settings().maxHeightDifference + 1) {
                npc.linkedMarker(marker.uuid());
                plugin.npcs().markDirty();
                result.npcsLinked.add(npc);
            }
        }
        changed(mapId);
        return result;
    }

    public DeleteResult deleteMarker(RoadMarker marker) {
        DeleteResult result = new DeleteResult();
        String mapId = marker.mapId();
        result.formerNeighbors = plugin.network().remove(marker).size();
        for (GpsNpc npc : plugin.npcs().linkedTo(marker.uuid())) {
            RoadMarker replacement = relinkNpc(npc);
            if (replacement != null) result.relinked.add(npc);
            else result.orphaned.add(npc);
        }
        plugin.editors().forgetMarker(marker.uuid());
        changed(mapId);
        return result;
    }

    public LinkCheck manualLink(RoadMarker a, RoadMarker b) {
        if (a.neighbors().contains(b.uuid())) return LinkCheck.ALREADY;
        LinkCheck c = check(a, b, true);
        if (c == LinkCheck.OK) {
            plugin.network().link(a, b);
            changed(a.mapId());
        }
        return c;
    }

    public boolean manualUnlink(RoadMarker a, RoadMarker b) {
        boolean ok = plugin.network().unlink(a, b);
        if (ok) changed(a.mapId());
        return ok;
    }

    // ---------- NPC ----------

    /** Находит NPC ближайший доступный маркер его карты в радиусе привязки (или null). */
    public RoadMarker relinkNpc(GpsNpc npc) {
        RoadMarker best = null;
        double bestD = Double.MAX_VALUE;
        double r = plugin.settings().npcLinkRadius;
        for (RoadMarker m : plugin.network().nearbyColumn(npc.world(), npc.x(), npc.z(), r)) {
            if (!m.mapId().equals(npc.mapId()) || plugin.zones().isBlocked(m)) continue;
            if (Math.abs(m.y() - npc.y()) > plugin.settings().maxHeightDifference + 1) continue;
            double d = m.distanceSq(npc.x(), npc.y() + 0.5, npc.z());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        UUID old = npc.linkedMarker();
        npc.linkedMarker(best == null ? null : best.uuid());
        if (old == null ? best != null : !old.equals(npc.linkedMarker())) {
            plugin.npcs().markDirty();
            plugin.requestSave();
        }
        return best;
    }

    /** После изменения зон: NPC, чей маркер оказался в зоне, ищут новый. */
    public List<GpsNpc> relinkNpcsAffectedByZones(String mapId) {
        List<GpsNpc> changedNpcs = new ArrayList<>();
        for (GpsNpc npc : plugin.npcs().ofMap(mapId)) {
            RoadMarker m = plugin.network().get(npc.linkedMarker());
            if (m == null || plugin.zones().isBlocked(m)) {
                UUID before = npc.linkedMarker();
                relinkNpc(npc);
                if (before == null ? npc.linkedMarker() != null : !before.equals(npc.linkedMarker())) {
                    changedNpcs.add(npc);
                }
            }
        }
        return changedNpcs;
    }

    public void zonesChanged(String mapId) {
        relinkNpcsAffectedByZones(mapId);
        changed(mapId);
    }

    /** Уведомляет маршруты и планирует сохранение. */
    public void changed(String mapId) {
        plugin.routes().onNetworkChanged(mapId);
        plugin.requestSave();
    }
}
