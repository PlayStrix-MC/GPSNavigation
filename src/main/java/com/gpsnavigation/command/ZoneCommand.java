package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.DeadZone;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.model.ZonePoint;
import com.gpsnavigation.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /gpszone — точки мёртвых зон (работают как дорожные маркеры, замкнутый контур = зона). */
public final class ZoneCommand extends BaseCommand {

    public ZoneCommand(GPSNavigation plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!perm(sender, "gps.admin.zone")) return true;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand": {
                Player p = player(sender);
                if (p == null) return true;
                p.getInventory().addItem(plugin.items().zoneWand());
                Msg.send(p, "<light_purple>Получен «Маркер мёртвой зоны». Ставьте точки вокруг области (ПКМ по блоку) — "
                        + "они соединятся сами, а замкнутая площадь станет мёртвой зоной.");
                return true;
            }
            case "show": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.admin.debug")) return true;
                boolean on = plugin.debug().toggle(p);
                Msg.send(p, on ? "<green>Отображение дорог и зон включено (видно только вам)." : "<yellow>Отображение выключено.");
                return true;
            }
            case "list": {
                String mapId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : null;
                StringBuilder sb = new StringBuilder("<light_purple>Мёртвые зоны:");
                int count = 0;
                for (TerrainMap map : plugin.maps().all()) {
                    if (mapId != null && !map.id().equals(mapId)) continue;
                    for (DeadZone z : plugin.zones().ofMap(map.id())) {
                        count++;
                        sb.append("\n<gray>  <white>").append(z.shortId()).append("</white> <dark_gray>[")
                                .append(Msg.esc(map.id())).append("]</dark_gray> ").append(z.describe());
                    }
                }
                if (count == 0) sb.append(" <gray>нет");
                int open = 0;
                for (ZonePoint p : plugin.zones().points()) {
                    if ((mapId == null || p.mapId().equals(mapId)) && plugin.zones().zoneOf(p) == null) open++;
                }
                if (open > 0) sb.append("\n<yellow>  Точек вне замкнутых контуров: ").append(open);
                Msg.send(sender, sb.toString());
                return true;
            }
            case "here": {
                Player p = player(sender);
                if (p == null) return true;
                DeadZone found = null;
                for (TerrainMap map : plugin.maps().inWorld(p.getWorld().getName())) {
                    found = plugin.zones().zoneAt(map.id(), p.getLocation().getX(), p.getLocation().getZ());
                    if (found != null) break;
                }
                if (found == null) Msg.send(p, "<gray>Вы не в мёртвой зоне.");
                else plugin.zoneEditor().sendZoneInfo(p, found);
                return true;
            }
            case "info": {
                ZonePoint point;
                if (args.length >= 2) {
                    point = plugin.zones().find(args[1]);
                } else {
                    Player p = player(sender);
                    if (p == null) return true;
                    point = plugin.zoneEditor().pick(p);
                    if (point == null) point = plugin.zoneEditor().nearest(p, 32);
                }
                if (point == null) {
                    Msg.send(sender, "<red>Точка зоны не найдена.");
                    return true;
                }
                plugin.zoneEditor().sendInfo(sender, point);
                return true;
            }
            case "remove":
            case "delete": {
                ZonePoint point = args.length >= 2 ? plugin.zones().find(args[1]) : null;
                if (point == null) {
                    Msg.send(sender, "<gray>Использование: <white>/gpszone remove <id точки></white>");
                    return true;
                }
                plugin.zoneEditor().delete(sender, point);
                return true;
            }
            case "link":
            case "unlink": {
                ZonePoint a = args.length >= 3 ? plugin.zones().find(args[1]) : null;
                ZonePoint b = args.length >= 3 ? plugin.zones().find(args[2]) : null;
                if (a == null || b == null) {
                    Msg.send(sender, "<gray>Использование: <white>/gpszone " + sub + " <id1> <id2>");
                    return true;
                }
                boolean ok = sub.equals("link") ? plugin.zones().link(a, b) : plugin.zones().unlink(a, b);
                if (ok) plugin.roads().zonesChanged(a.mapId());
                Msg.send(sender, ok ? "<green>Готово: " + a.shortId() + " — " + b.shortId() : "<yellow>Ничего не изменилось.");
                return true;
            }
            default:
                Msg.send(sender, "<gray>/gpszone wand</gray> <dark_gray>—</dark_gray> <white>жезл точек мёртвой зоны"
                        + "\n<gray>/gpszone show</gray> <dark_gray>—</dark_gray> <white>показать/скрыть дороги и зоны"
                        + "\n<gray>/gpszone list [карта]</gray>, <gray>/gpszone here</gray>, <gray>/gpszone info [id]"
                        + "\n<gray>/gpszone remove <id></gray>, <gray>/gpszone link|unlink <id1> <id2>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("gps.admin.zone")) return List.of();
        if (args.length == 1) {
            List<String> subs = new java.util.ArrayList<>(List.of("wand", "list", "here", "info", "remove", "link", "unlink"));
            if (sender.hasPermission("gps.admin.debug")) subs.add(1, "show");
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("list")) return filter(mapIds(), args[1]);
        if (args.length == 2 && List.of("info", "remove", "delete", "link", "unlink").contains(sub)) return filter(zoneIds(), args[1]);
        if (args.length == 3 && (sub.equals("link") || sub.equals("unlink"))) return filter(zoneIds(), args[2]);
        return List.of();
    }
}
