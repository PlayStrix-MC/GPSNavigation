package com.gpsnavigation.editor;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.model.ZonePoint;
import com.gpsnavigation.util.Msg;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Жезл «Маркер мёртвой зоны». Работает как жезл дорог: точки ставятся без блоков и сами соединяются
 * с соседними точками в радиусе zone.link-radius. Площадь внутри замкнутого контура — мёртвая зона.
 */
public final class ZoneEditor {

    private final GPSNavigation plugin;

    public ZoneEditor(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    public void handleRight(Player p, Block clicked, BlockFace face) {
        ZonePoint picked = pick(p);
        if (p.isSneaking()) {
            linkStep(p, picked, false);
            return;
        }
        if (clicked == null || face == null) {
            if (picked != null) sendInfo(p, picked);
            else Msg.send(p, "<gray>Нажмите ПКМ по блоку, чтобы поставить точку зоны.");
            return;
        }
        TerrainMap map = plugin.editors().resolveMap(p);
        if (map == null) return;
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        ZonePoint existing = plugin.zones().findAt(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        if (existing != null) {
            Msg.send(p, "<yellow>Здесь уже есть точка <white>" + existing.shortId() + "</white> — дубликат не создан.");
            return;
        }
        Set<String> zonesBefore = zoneLabels(map.id());
        ZonePoint point = plugin.zones().create(map.id(), target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        List<String> linked = new ArrayList<>();
        for (ZonePoint o : plugin.zones().nearbyColumn(point.world(), point.cx(), point.cz(), plugin.settings().zoneLinkRadius)) {
            if (o == point || !o.mapId().equals(map.id())) continue;
            if (plugin.zones().link(point, o)) linked.add(o.shortId());
        }
        plugin.roads().zonesChanged(map.id());
        StringBuilder sb = new StringBuilder("<light_purple>Точка зоны <white>" + point.shortId() + "</white> <gray>(" + point.coords() + ")");
        sb.append(linked.isEmpty()
                ? "\n<yellow>  Соседних точек в радиусе " + Msg.fmt(plugin.settings().zoneLinkRadius) + " нет."
                : "\n<gray>  Соединена с: <white>" + String.join(", ", linked));
        DeadZone zone = plugin.zones().zoneOf(point);
        if (zone != null) {
            sb.append(zonesBefore.contains(zone.shortId()) ? "\n<gray>  Входит в мёртвую зону " : "\n<light_purple>  Контур замкнут — мёртвая зона ")
                    .append("<white>").append(zone.shortId()).append("</white> <gray>(").append(zone.describe()).append(")");
        } else {
            sb.append("\n<gray>  Контур ещё не замкнут.");
        }
        Msg.send(p, sb.toString());
    }

    public void handleLeft(Player p) {
        ZonePoint picked = pick(p);
        if (p.isSneaking()) {
            linkStep(p, picked, true);
            return;
        }
        if (picked == null) {
            Msg.send(p, "<gray>Наведитесь на точку зоны, чтобы удалить её.");
            return;
        }
        if (!plugin.editors().confirm(p, "del-zone:" + picked.uuid())) {
            Msg.send(p, "<yellow>Нажмите ЛКМ ещё раз, чтобы удалить точку <white>" + picked.shortId() + "</white>.");
            return;
        }
        delete(p, picked);
    }

    public void delete(CommandSender sender, ZonePoint point) {
        int links = plugin.zones().remove(point);
        plugin.editors().forgetMarker(point.uuid());
        plugin.roads().zonesChanged(point.mapId());
        Msg.send(sender, "<green>Точка зоны <white>" + point.shortId() + "</white> удалена, разорвано связей: " + links + ".");
    }

    private void linkStep(Player p, ZonePoint picked, boolean unlink) {
        if (picked == null) {
            Msg.send(p, "<gray>Наведитесь на точку зоны.");
            return;
        }
        EditorSessions.Selection sel = plugin.editors().selection(p);
        ZonePoint first = sel == null || !sel.zone ? null : plugin.zones().get(sel.marker);
        if (first == null || sel.unlink != unlink) {
            plugin.editors().select(p, picked.uuid(), unlink, true);
            Msg.send(p, "<light_purple>Выбрана <white>" + picked.shortId() + "</white>. " + (unlink
                    ? "Shift+ЛКМ по второй точке — разорвать связь."
                    : "Shift+ПКМ по второй точке — соединить."));
            return;
        }
        plugin.editors().clearSelection(p);
        if (first == picked) {
            Msg.send(p, "<gray>Выделение снято.");
            return;
        }
        boolean ok = unlink ? plugin.zones().unlink(first, picked) : plugin.zones().link(first, picked);
        if (ok) plugin.roads().zonesChanged(first.mapId());
        Msg.send(p, ok
                ? "<green>" + (unlink ? "Связь разорвана: " : "Соединены: ") + "<white>" + first.shortId() + " — " + picked.shortId()
                : unlink ? "<yellow>Точки не были соединены." : "<yellow>Точки уже соединены или на разных картах.");
    }

    /** Точка зоны, на которую смотрит игрок. */
    public ZonePoint pick(Player p) {
        double max = plugin.settings().pickDistance;
        Location eye = p.getEyeLocation();
        Vector origin = eye.toVector();
        Vector dir = eye.getDirection().normalize();
        double limit = max;
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, max, FluidCollisionMode.NEVER, true);
        if (hit != null) limit = hit.getHitPosition().distance(origin) + 1.0;
        ZonePoint best = null;
        double bestT = Double.MAX_VALUE;
        for (ZonePoint m : plugin.zones().nearby(p.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(), max + 1)) {
            Vector oc = new Vector(m.cx() - origin.getX(), m.cy() - origin.getY(), m.cz() - origin.getZ());
            double t = oc.dot(dir);
            if (t < 0 || t > limit) continue;
            double perp2 = oc.lengthSquared() - t * t;
            if (perp2 <= 0.64 && t < bestT) {
                bestT = t;
                best = m;
            }
        }
        return best;
    }

    public ZonePoint nearest(Player p, double radius) {
        Location l = p.getLocation();
        ZonePoint best = null;
        double bestD = Double.MAX_VALUE;
        for (ZonePoint m : plugin.zones().nearby(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), radius)) {
            double d = m.distanceSq(l.getX(), l.getY(), l.getZ());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    private Set<String> zoneLabels(String mapId) {
        Set<String> set = new HashSet<>();
        for (DeadZone z : plugin.zones().ofMap(mapId)) set.add(z.shortId());
        return set;
    }

    /** {маркеров внутри, связей через зону}. */
    public int[] stats(DeadZone zone) {
        int inside = 0;
        int links = 0;
        Set<String> seen = new HashSet<>();
        for (RoadMarker m : plugin.network().ofMap(zone.mapId())) {
            if (zone.contains(m.cx(), m.cz())) inside++;
            for (UUID n : m.neighbors()) {
                RoadMarker o = plugin.network().get(n);
                if (o == null) continue;
                String key = m.uuid().compareTo(o.uuid()) < 0 ? m.uuid() + ":" + o.uuid() : o.uuid() + ":" + m.uuid();
                if (seen.add(key) && zone.intersectsSegment(m.cx(), m.cz(), o.cx(), o.cz())) links++;
            }
        }
        return new int[]{inside, links};
    }

    public void sendInfo(CommandSender to, ZonePoint point) {
        List<String> n = new ArrayList<>();
        for (UUID id : point.neighbors()) {
            ZonePoint o = plugin.zones().get(id);
            if (o != null) n.add(o.shortId());
        }
        DeadZone zone = plugin.zones().zoneOf(point);
        StringBuilder sb = new StringBuilder("<light_purple>Точка зоны <white><bold>" + point.shortId() + "</bold></white>"
                + "\n<gray>  Координаты: <white>" + point.coords() + "</white>, карта <white>" + Msg.esc(point.mapId())
                + "\n<gray>  Соединена с: " + (n.isEmpty() ? "<red>ни с чем" : "<white>" + String.join(", ", n)));
        if (zone == null) {
            sb.append("\n<yellow>  Контур не замкнут — мёртвой зоны нет.");
        } else {
            int[] st = stats(zone);
            sb.append("\n<gray>  Мёртвая зона <white>").append(zone.shortId()).append("</white>: ").append(zone.describe())
                    .append("\n<gray>  Исключено маркеров: <white>").append(st[0]).append("</white>, перекрыто связей: <white>").append(st[1]);
        }
        Msg.send(to, sb.toString());
    }

    public void sendZoneInfo(CommandSender to, DeadZone zone) {
        int[] st = stats(zone);
        Msg.send(to, "<light_purple>Мёртвая зона <white><bold>" + zone.shortId() + "</bold></white> <gray>(" + zone.describe() + ")"
                + "\n<gray>  Карта: <white>" + Msg.esc(zone.mapId()) + "</white>, точки: <white>" + String.join(", ", zone.memberIds())
                + "\n<gray>  Исключено маркеров: <white>" + st[0] + "</white>, перекрыто связей: <white>" + st[1]);
    }
}
