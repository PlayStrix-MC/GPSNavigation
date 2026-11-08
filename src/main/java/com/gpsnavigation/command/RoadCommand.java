package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.core.RoadService;
import com.gpsnavigation.model.MarkerType;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /road — дорожная сеть. */
public final class RoadCommand extends BaseCommand {

    public RoadCommand(GPSNavigation plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.admin.road")) return true;
                p.getInventory().addItem(plugin.items().roadWand());
                Msg.send(p, "<green>Получен «Дорожный маркер». ПКМ по блоку — создать маркер. Подсказки — в описании предмета.");
                return true;
            }
            case "show": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.admin.debug")) return true;
                boolean on = plugin.debug().toggle(p);
                Msg.send(p, on
                        ? "<green>Отображение дорожной сети включено <gray>(радиус " + Msg.fmt(plugin.settings().debugRadius) + "). Видно только вам."
                        : "<yellow>Отображение дорожной сети выключено.");
                return true;
            }
            case "info": {
                if (!sender.hasPermission("gps.admin.debug") && !perm(sender, "gps.admin.road")) return true;
                RoadMarker m;
                if (args.length >= 2) {
                    m = plugin.network().find(args[1]);
                } else {
                    Player p = player(sender);
                    if (p == null) return true;
                    m = plugin.roadEditor().pick(p);
                    if (m == null) m = plugin.roadEditor().nearest(p, 32);
                }
                if (m == null) {
                    Msg.send(sender, "<red>Маркер не найден" + (args.length >= 2 ? "." : " поблизости (радиус 32)."));
                    return true;
                }
                plugin.roadEditor().sendInfo(sender, m);
                return true;
            }
            case "remove": {
                if (!perm(sender, "gps.admin.road")) return true;
                if (args.length < 2) {
                    Msg.send(sender, "<gray>Использование: <white>/road remove <id>");
                    return true;
                }
                RoadMarker m = plugin.network().find(args[1]);
                if (m == null) {
                    Msg.send(sender, "<red>Маркер не найден.");
                    return true;
                }
                plugin.roadEditor().delete(sender, m);
                return true;
            }
            case "link":
            case "unlink": {
                if (!perm(sender, "gps.admin.road")) return true;
                if (args.length < 3) {
                    Msg.send(sender, "<gray>Использование: <white>/road " + sub + " <id1> <id2>");
                    return true;
                }
                RoadMarker a = plugin.network().find(args[1]);
                RoadMarker b = plugin.network().find(args[2]);
                if (a == null || b == null) {
                    Msg.send(sender, "<red>Маркер не найден.");
                    return true;
                }
                if (sub.equals("link")) {
                    RoadService.LinkCheck c = plugin.roads().manualLink(a, b);
                    Msg.send(sender, c == RoadService.LinkCheck.OK
                            ? "<green>Маркеры " + a.shortId() + " — " + b.shortId() + " связаны."
                            : "<red>Связь невозможна: " + c.text + ".");
                } else {
                    Msg.send(sender, plugin.roads().manualUnlink(a, b)
                            ? "<green>Связь " + a.shortId() + " — " + b.shortId() + " удалена."
                            : "<yellow>Маркеры не были связаны.");
                }
                return true;
            }
            case "type": {
                if (!perm(sender, "gps.admin.road")) return true;
                if (args.length < 3) {
                    Msg.send(sender, "<gray>Использование: <white>/road type <id> <ROAD|ENTRANCE>");
                    return true;
                }
                RoadMarker m = plugin.network().find(args[1]);
                if (m == null) {
                    Msg.send(sender, "<red>Маркер не найден.");
                    return true;
                }
                try {
                    m.type(MarkerType.valueOf(args[2].toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    Msg.send(sender, "<red>Тип: ROAD или ENTRANCE.");
                    return true;
                }
                plugin.network().markDirty();
                plugin.roads().changed(m.mapId());
                Msg.send(sender, "<green>Тип маркера " + m.shortId() + ": <white>" + m.type().name());
                return true;
            }
            case "list": {
                if (!sender.hasPermission("gps.admin.debug") && !perm(sender, "gps.admin.road")) return true;
                String mapId = args.length >= 2 ? args[1] : null;
                List<RoadMarker> list = new ArrayList<>();
                for (RoadMarker m : plugin.network().all()) {
                    if (mapId == null || m.mapId().equalsIgnoreCase(mapId)) list.add(m);
                }
                int isolated = 0;
                StringBuilder sb = new StringBuilder();
                for (RoadMarker m : list) {
                    if (m.neighbors().isEmpty()) isolated++;
                }
                sb.append("<green>Маркеров: <white>").append(list.size()).append("</white>, без связей: <red>").append(isolated);
                int shown = 0;
                for (RoadMarker m : list) {
                    if (shown++ >= 30) {
                        sb.append("\n<gray>  … и ещё ").append(list.size() - 30);
                        break;
                    }
                    sb.append("\n<gray>  <white>").append(m.shortId()).append("</white> ").append(m.coords())
                            .append(" <dark_gray>[").append(Msg.esc(m.mapId())).append("]</dark_gray> связей: ").append(m.neighbors().size());
                }
                Msg.send(sender, sb.toString());
                return true;
            }
            default: {
                Msg.send(sender, "<gray>/road wand</gray> <dark_gray>—</dark_gray> <white>жезл дорожных маркеров"
                        + "\n<gray>/road show</gray> <dark_gray>—</dark_gray> <white>показать/скрыть сеть"
                        + "\n<gray>/road info [id]</gray> <dark_gray>—</dark_gray> <white>информация о ближайшем маркере"
                        + "\n<gray>/road remove <id></gray>, <gray>/road link|unlink <id1> <id2></gray>"
                        + "\n<gray>/road type <id> <ROAD|ENTRANCE></gray>, <gray>/road list [карта]</gray>");
                Player p = sender instanceof Player ? (Player) sender : null;
                if (p != null) {
                    TerrainMap map = plugin.maps().get(plugin.editors().selectedMapId(p));
                    Msg.raw(p, "<gray>  Карта редактирования: <white>" + (map == null ? "не выбрана (/gpsmap select)" : Msg.esc(map.id())));
                }
                return true;
            }
        }
    }

    private static final String ROAD = "gps.admin.road";
    private static final String DEBUG = "gps.admin.debug";
    private static final java.util.Map<String, String[]> SUBS = subs(
            "wand", p(ROAD),
            "show", p(DEBUG),
            "info", p(ROAD, DEBUG),
            "list", p(ROAD, DEBUG),
            "remove", p(ROAD),
            "link", p(ROAD),
            "unlink", p(ROAD),
            "type", p(ROAD));

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(allowed(sender, SUBS), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!can(sender, SUBS, sub)) return List.of();
        if (args.length == 2 && List.of("info", "remove", "link", "unlink", "type").contains(sub)) return filter(markerIds(), args[1]);
        if (args.length == 3 && (sub.equals("link") || sub.equals("unlink"))) return filter(markerIds(), args[2]);
        if (args.length == 3 && sub.equals("type")) return filter(List.of("ROAD", "ENTRANCE"), args[2]);
        if (args.length == 2 && sub.equals("list")) return filter(mapIds(), args[1]);
        return List.of();
    }
}
