package com.gpsnavigation.debug;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.Settings;
import com.gpsnavigation.editor.EditorSessions;
import com.gpsnavigation.item.SpecialItems;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.ZonePoint;
import com.gpsnavigation.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Режим /road show (/gpszone show): частицы маркеров, точек зон и связей на их реальной высоте + персональные подписи ID.
 * Частицы перерисовываются каждые debug.update-interval-ticks (по умолчанию 4), поэтому не мигают.
 * Всё отправляется только конкретному администратору (Player#spawnParticle и скрытые TextDisplay),
 * другие игроки ничего не видят. Подписи не сохраняются в мире.
 * Пока админ держит жезл, сеть (без подписей) показывается и без /road show.
 */
public final class DebugRenderer {

    private static final Particle.DustOptions OK = new Particle.DustOptions(Color.LIME, 1.2f);
    private static final Particle.DustOptions ISOLATED = new Particle.DustOptions(Color.RED, 1.5f);
    private static final Particle.DustOptions IN_ZONE = new Particle.DustOptions(Color.PURPLE, 1.5f);
    private static final Particle.DustOptions SELECTED = new Particle.DustOptions(Color.YELLOW, 2.0f);
    private static final Particle.DustOptions LINK = new Particle.DustOptions(Color.fromRGB(80, 220, 80), 0.7f);
    private static final Particle.DustOptions BAD_LINK = new Particle.DustOptions(Color.ORANGE, 0.8f);
    private static final Particle.DustOptions ZONE = new Particle.DustOptions(Color.fromRGB(150, 0, 200), 1.2f);
    private static final Particle.DustOptions ZONE_OPEN = new Particle.DustOptions(Color.fromRGB(255, 120, 200), 1.2f);
    private static final Particle.DustOptions RADIUS = new Particle.DustOptions(Color.WHITE, 0.6f);
    private static final Particle.DustOptions NPC = new Particle.DustOptions(Color.YELLOW, 1.4f);

    private final GPSNavigation plugin;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<UUID, Map<String, TextDisplay>> labels = new HashMap<>();
    private final Map<UUID, Map<String, String>> labelTexts = new HashMap<>();

    public DebugRenderer(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player p) {
        if (enabled.remove(p.getUniqueId())) {
            clearLabels(p.getUniqueId());
            return false;
        }
        enabled.add(p.getUniqueId());
        return true;
    }

    public boolean isEnabled(Player p) {
        return enabled.contains(p.getUniqueId());
    }

    public void remove(UUID player) {
        enabled.remove(player);
        clearLabels(player);
    }

    public void clearAll() {
        for (UUID id : new HashSet<>(labels.keySet())) clearLabels(id);
        enabled.clear();
    }

    private void clearLabels(UUID player) {
        Map<String, TextDisplay> map = labels.remove(player);
        labelTexts.remove(player);
        if (map == null) return;
        for (TextDisplay t : map.values()) plugin.visuals().remove(t);
    }

    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean toggled = enabled.contains(p.getUniqueId()) && p.hasPermission("gps.admin.debug");
            SpecialItems.Type held = plugin.items().typeOf(p.getInventory().getItemInMainHand());
            boolean wand = (held == SpecialItems.Type.ROAD_WAND && p.hasPermission("gps.admin.road"))
                    || (held == SpecialItems.Type.ZONE_WAND && p.hasPermission("gps.admin.zone"));
            if (!toggled && !wand) {
                if (labels.containsKey(p.getUniqueId())) clearLabels(p.getUniqueId());
                continue;
            }
            draw(p, toggled, held);
        }
    }

    private void draw(Player p, boolean withLabels, SpecialItems.Type held) {
        Settings s = plugin.settings();
        Location loc = p.getLocation();
        String world = p.getWorld().getName();
        double r = s.debugRadius;
        int[] budget = {s.particleBudget};
        Set<String> wantedLabels = new HashSet<>();
        EditorSessions.Selection sel = plugin.editors().selection(p);

        List<RoadMarker> nearby = plugin.network().nearby(world, loc.getX(), loc.getY(), loc.getZ(), r);
        for (RoadMarker m : nearby) {
            boolean inZone = plugin.zones().isBlocked(m);
            Particle.DustOptions color = inZone ? IN_ZONE
                    : m.neighbors().isEmpty() ? ISOLATED : OK;
            if (sel != null && sel.marker.equals(m.uuid())) color = SELECTED;
            for (int i = 0; i < 3 && budget[0] > 0; i++, budget[0]--) {
                p.spawnParticle(Particle.DUST, m.cx(), m.y() + 0.3 + i * 0.4, m.cz(), 1, 0, 0, 0, 0, color);
            }
            for (UUID nid : m.neighbors()) {
                RoadMarker o = plugin.network().get(nid);
                if (o == null) continue;
                // Каждую связь рисуем один раз (если сосед вне радиуса — рисуем от этого маркера).
                if (m.uuid().compareTo(o.uuid()) > 0 && o.distanceSq(loc.getX(), loc.getY(), loc.getZ()) <= r * r) continue;
                boolean bad = !o.mapId().equals(m.mapId()) || plugin.zones().segmentBlocked(m, o)
                        || plugin.zones().isBlocked(o) || inZone;
                line(p, m.cx(), m.y() + 0.5, m.cz(), o.cx(), o.y() + 0.5, o.cz(), bad ? BAD_LINK : LINK, budget);
            }
            if (withLabels && s.debugLabels) {
                String key = "m:" + m.uuid();
                wantedLabels.add(key);
                String state = inZone ? "<dark_purple>в мёртвой зоне" : m.neighbors().isEmpty() ? "<red>без связей"
                        : "<gray>связей: " + m.neighbors().size();
                String text = "<green><bold>" + m.shortId() + "</bold>\n" + state;
                label(p, key, new Location(p.getWorld(), m.cx(), m.y() + 1.4, m.cz()), text);
            }
        }

        // Точки мёртвых зон и их связи — на реальной высоте точек.
        for (ZonePoint zp : plugin.zones().nearby(world, loc.getX(), loc.getY(), loc.getZ(), r)) {
            DeadZone zone = plugin.zones().zoneOf(zp);
            Particle.DustOptions color = zone != null ? ZONE : ZONE_OPEN;
            if (sel != null && sel.zone && sel.marker.equals(zp.uuid())) color = SELECTED;
            for (int i = 0; i < 3 && budget[0] > 0; i++, budget[0]--) {
                p.spawnParticle(Particle.DUST, zp.cx(), zp.y() + 0.3 + i * 0.4, zp.cz(), 1, 0, 0, 0, 0, color);
            }
            for (UUID nid : zp.neighbors()) {
                ZonePoint o = plugin.zones().get(nid);
                if (o == null) continue;
                if (zp.uuid().compareTo(o.uuid()) > 0 && o.distanceSq(loc.getX(), loc.getY(), loc.getZ()) <= r * r) continue;
                line(p, zp.cx(), zp.y() + 0.5, zp.cz(), o.cx(), o.y() + 0.5, o.cz(), zone != null ? ZONE : ZONE_OPEN, budget);
            }
            if (withLabels && s.debugLabels) {
                String key = "z:" + zp.uuid();
                wantedLabels.add(key);
                label(p, key, new Location(p.getWorld(), zp.cx(), zp.y() + 1.4, zp.cz()),
                        "<light_purple><bold>" + zp.shortId() + "</bold>\n"
                                + (zone != null ? "<dark_purple>зона " + zone.shortId() : "<red>контур не замкнут"));
            }
        }

        for (GpsNpc n : plugin.npcs().all()) {
            if (!n.world().equals(world)) continue;
            double dx = n.x() - loc.getX();
            double dz = n.z() - loc.getZ();
            if (dx * dx + dz * dz > r * r) continue;
            p.spawnParticle(Particle.DUST, n.x(), n.y() + 1.0, n.z(), 3, 0.2, 0.4, 0.2, 0, NPC);
            RoadMarker linked = plugin.network().get(n.linkedMarker());
            if (linked != null) {
                line(p, n.x(), n.y() + 0.5, n.z(), linked.cx(), linked.y() + 0.5, linked.cz(), NPC, budget);
            }
            if (withLabels && s.debugLabels) {
                String key = "n:" + n.id();
                wantedLabels.add(key);
                label(p, key, new Location(p.getWorld(), n.x(), n.y() + 2.4, n.z()),
                        "<yellow>NPC " + Msg.esc(n.displayName()) + "\n" + (linked == null ? "<red>не привязан" : "<gray>→ " + linked.shortId()));
            }
        }

        // Пока в руке жезл — круг радиуса соединения вокруг будущей точки (на высоте блока прицела).
        if (held == SpecialItems.Type.ROAD_WAND || held == SpecialItems.Type.ZONE_WAND) {
            Block target = p.getTargetBlockExact((int) Math.ceil(s.pickDistance));
            if (target != null) {
                double y = target.getY() + 1.1;
                double radius = held == SpecialItems.Type.ROAD_WAND ? s.linkRadius : s.zoneLinkRadius;
                circle(p, target.getX() + 0.5, y, target.getZ() + 0.5, radius, RADIUS, budget);
            }
        }

        // Убираем подписи, вышедшие из радиуса.
        Map<String, TextDisplay> own = labels.get(p.getUniqueId());
        if (own != null) {
            Map<String, String> texts = labelTexts.get(p.getUniqueId());
            Iterator<Map.Entry<String, TextDisplay>> it = own.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, TextDisplay> e = it.next();
                if (!wantedLabels.contains(e.getKey()) || !e.getValue().isValid()) {
                    plugin.visuals().remove(e.getValue());
                    it.remove();
                    if (texts != null) texts.remove(e.getKey());
                }
            }
        }
    }

    private void label(Player p, String key, Location loc, String text) {
        Map<String, TextDisplay> own = labels.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Map<String, String> texts = labelTexts.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        TextDisplay existing = own.get(key);
        if (existing != null && existing.isValid()) {
            if (!text.equals(texts.get(key))) {
                existing.text(Msg.mm(text));
                texts.put(key, text);
            }
            return;
        }
        TextDisplay created = plugin.visuals().spawnLabel(p, loc, Msg.mm(text));
        if (created != null) {
            own.put(key, created);
            texts.put(key, text);
        }
    }

    private void line(Player p, double x1, double y1, double z1, double x2, double y2, double z2,
                      Particle.DustOptions color, int[] budget) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) (len / 0.5));
        for (int i = 1; i < steps && budget[0] > 0; i++, budget[0]--) {
            double t = (double) i / steps;
            p.spawnParticle(Particle.DUST, x1 + dx * t, y1 + dy * t, z1 + dz * t, 1, 0, 0, 0, 0, color);
        }
    }

    private void circle(Player p, double cx, double y, double cz, double radius, Particle.DustOptions color, int[] budget) {
        int steps = Math.max(12, (int) (radius * 2 * Math.PI / 0.6));
        for (int i = 0; i < steps && budget[0] > 0; i++, budget[0]--) {
            double a = 2 * Math.PI * i / steps;
            p.spawnParticle(Particle.DUST, cx + Math.cos(a) * radius, y, cz + Math.sin(a) * radius, 1, 0, 0, 0, 0, color);
        }
    }

}
