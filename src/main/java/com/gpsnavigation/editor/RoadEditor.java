package com.gpsnavigation.editor;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.core.RoadService;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.TerrainMap;
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
import java.util.List;
import java.util.UUID;

/** Работа жезла «Дорожный маркер». Блок никогда не ставится в мир — маркер создаётся только в данных. */
public final class RoadEditor {

    private final GPSNavigation plugin;

    public RoadEditor(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    public void handleRight(Player p, Block clicked, BlockFace face) {
        RoadMarker picked = pick(p);
        if (p.isSneaking()) {
            linkStep(p, picked, false);
            return;
        }
        if (clicked == null || face == null) {
            if (picked != null) sendInfo(p, picked);
            else Msg.send(p, "<gray>Нажмите ПКМ по блоку, чтобы поставить маркер над ним.");
            return;
        }
        TerrainMap map = plugin.editors().resolveMap(p);
        if (map == null) return;
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        RoadService.CreateResult r = plugin.roads().createMarker(map.id(), target.getWorld(),
                target.getX(), target.getY(), target.getZ());
        if (r.existing != null) {
            Msg.send(p, "<yellow>Здесь уже есть маркер <white>" + r.existing.shortId() + "</white> — дубликат не создан.");
            return;
        }
        if (r.zone != null) {
            Msg.send(p, "<red>Точка находится в мёртвой зоне <white>" + r.zone.shortId() + "</white> — маркер не создан.");
            return;
        }
        RoadMarker m = r.marker;
        StringBuilder sb = new StringBuilder("<green>Создан маркер <white>" + m.shortId() + "</white> <gray>(" + m.coords()
                + ", карта " + Msg.esc(map.id()) + ")</gray>");
        if (r.linked.isEmpty()) {
            sb.append("\n<yellow>  Соседей в радиусе ").append(Msg.fmt(plugin.settings().linkRadius)).append(" нет — маркер пока без связей.");
        } else {
            List<String> ids = new ArrayList<>();
            for (RoadMarker n : r.linked) ids.add(n.shortId());
            sb.append("\n<gray>  Связан с: <white>").append(String.join(", ", ids));
        }
        if (!r.rejected.isEmpty()) {
            sb.append("\n<red>  Отклонено: <gray>").append(Msg.esc(String.join("; ", r.rejected)));
        }
        for (GpsNpc npc : r.npcsLinked) {
            sb.append("\n<aqua>  NPC ").append(Msg.esc(npc.displayName())).append(" привязан к этому маркеру");
        }
        Msg.send(p, sb.toString());
    }

    public void handleLeft(Player p) {
        RoadMarker picked = pick(p);
        if (p.isSneaking()) {
            linkStep(p, picked, true);
            return;
        }
        if (picked == null) {
            Msg.send(p, "<gray>Наведитесь на маркер (включите <white>/road show</white>), чтобы удалить его.");
            return;
        }
        if (!plugin.editors().confirm(p, "del-marker:" + picked.uuid())) {
            Msg.send(p, "<yellow>Нажмите ЛКМ ещё раз, чтобы удалить маркер <white>" + picked.shortId() + "</white>.");
            return;
        }
        delete(p, picked);
    }

    public void delete(CommandSender sender, RoadMarker marker) {
        RoadService.DeleteResult d = plugin.roads().deleteMarker(marker);
        StringBuilder sb = new StringBuilder("<green>Маркер <white>" + marker.shortId() + "</white> удалён, разорвано связей: " + d.formerNeighbors + ".");
        for (GpsNpc n : d.relinked) {
            RoadMarker nm = plugin.network().get(n.linkedMarker());
            sb.append("\n<aqua>  NPC ").append(Msg.esc(n.displayName())).append(" перепривязан к ").append(nm == null ? "?" : nm.shortId());
        }
        for (GpsNpc n : d.orphaned) {
            sb.append("\n<red>  NPC ").append(Msg.esc(n.displayName())).append(" остался без привязки — GPS к нему не построит маршрут");
        }
        Msg.send(sender, sb.toString());
    }

    private void linkStep(Player p, RoadMarker picked, boolean unlink) {
        if (picked == null) {
            Msg.send(p, "<gray>Наведитесь на маркер.");
            return;
        }
        EditorSessions.Selection sel = plugin.editors().selection(p);
        RoadMarker first = sel == null || sel.zone ? null : plugin.network().get(sel.marker);
        if (first == null || sel.zone || sel.unlink != unlink) {
            plugin.editors().select(p, picked.uuid(), unlink, false);
            Msg.send(p, "<aqua>Выбран <white>" + picked.shortId() + "</white>. " + (unlink
                    ? "Shift+ЛКМ по второму маркеру — разорвать связь."
                    : "Shift+ПКМ по второму маркеру — связать."));
            return;
        }
        plugin.editors().clearSelection(p);
        if (first == picked) {
            Msg.send(p, "<gray>Выделение снято.");
            return;
        }
        if (unlink) {
            if (plugin.roads().manualUnlink(first, picked)) {
                Msg.send(p, "<green>Связь <white>" + first.shortId() + " — " + picked.shortId() + "</white> удалена.");
            } else {
                Msg.send(p, "<yellow>Маркеры не были связаны.");
            }
        } else {
            RoadService.LinkCheck c = plugin.roads().manualLink(first, picked);
            if (c == RoadService.LinkCheck.OK) {
                Msg.send(p, "<green>Маркеры <white>" + first.shortId() + " — " + picked.shortId() + "</white> связаны.");
            } else {
                Msg.send(p, "<red>Связь невозможна: " + c.text + ".");
            }
        }
    }

    /** Маркер, на который смотрит игрок (луч из глаз, до первого твёрдого блока). */
    public RoadMarker pick(Player p) {
        double max = plugin.settings().pickDistance;
        Location eye = p.getEyeLocation();
        Vector origin = eye.toVector();
        Vector dir = eye.getDirection().normalize();
        double limit = max;
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, max, FluidCollisionMode.NEVER, true);
        if (hit != null) limit = hit.getHitPosition().distance(origin) + 1.0;
        RoadMarker best = null;
        double bestT = Double.MAX_VALUE;
        for (RoadMarker m : plugin.network().nearby(p.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(), max + 1)) {
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

    /** Ближайший маркер в текущем мире игрока (для /road info). */
    public RoadMarker nearest(Player p, double radius) {
        Location l = p.getLocation();
        RoadMarker best = null;
        double bestD = Double.MAX_VALUE;
        for (RoadMarker m : plugin.network().nearby(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), radius)) {
            double d = m.distanceSq(l.getX(), l.getY(), l.getZ());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    public void sendInfo(CommandSender to, RoadMarker m) {
        TerrainMap map = plugin.maps().get(m.mapId());
        List<String> neighbors = new ArrayList<>();
        for (UUID n : m.neighbors()) {
            RoadMarker o = plugin.network().get(n);
            if (o == null) continue;
            String state = plugin.zones().segmentBlocked(m, o) ? " <red>(через зону)</red>" : "";
            neighbors.add("<white>" + o.shortId() + "</white>" + state);
        }
        List<String> npcs = new ArrayList<>();
        for (GpsNpc n : plugin.npcs().linkedTo(m.uuid())) npcs.add(Msg.esc(n.displayName()) + " <dark_gray>(" + n.id() + ")</dark_gray>");
        DeadZone zone = plugin.zones().zoneAt(m.mapId(), m.cx(), m.cz());
        StringBuilder sb = new StringBuilder();
        sb.append("<green>Маркер <white><bold>").append(m.shortId()).append("</bold></white> <dark_gray>").append(m.uuid()).append("</dark_gray>");
        sb.append("\n<gray>  Координаты: <white>").append(m.coords()).append("</white> <gray>мир <white>").append(Msg.esc(m.world()));
        sb.append("\n<gray>  Карта: <white>").append(map == null ? Msg.esc(m.mapId()) + " <red>(не найдена)" : Msg.esc(map.displayName()) + " <dark_gray>(" + map.id() + ")");
        sb.append("\n<gray>  Соединений: <white>").append(neighbors.size());
        sb.append("\n<gray>  Соседи: ").append(neighbors.isEmpty() ? "<red>нет" : String.join("<gray>, ", neighbors));
        sb.append("\n<gray>  Привязанные NPC: <white>").append(npcs.isEmpty() ? "нет" : String.join("<gray>, <white>", npcs));
        if (zone != null) sb.append("\n<red>  Внутри мёртвой зоны ").append(zone.shortId()).append(" — исключён из маршрутов");
        if (!m.settings().isEmpty()) sb.append("\n<gray>  Настройки: <white>").append(Msg.esc(m.settings().toString()));
        Msg.send(to, sb.toString());
    }
}
