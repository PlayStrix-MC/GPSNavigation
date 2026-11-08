package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.route.ActiveRoute;
import com.gpsnavigation.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /gps — альтернатива путеводителю: открыть навигатор, start, stop, status, give, reload. */
public final class GpsCommand extends BaseCommand {

    public GpsCommand(GPSNavigation plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Player p = player(sender);
            if (p == null || !perm(p, "gps.use")) return true;
            plugin.gui().openMain(p, 0);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.use")) return true;
                if (args.length < 2) {
                    Msg.send(p, "<gray>Использование: <white>/gps start <npc>");
                    return true;
                }
                GpsNpc npc = plugin.npcs().get(args[1]);
                if (npc == null) {
                    Msg.send(p, "<red>NPC <white>" + Msg.esc(args[1]) + "</white> не найден.");
                    return true;
                }
                plugin.routes().start(p, npc);
                return true;
            }
            case "stop": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.use")) return true;
                if (plugin.routes().get(p.getUniqueId()) == null) Msg.send(p, "<gray>Активного маршрута нет.");
                else plugin.routes().stop(p.getUniqueId(), true);
                return true;
            }
            case "status": {
                Player p = player(sender);
                if (p == null || !perm(p, "gps.use")) return true;
                ActiveRoute r = plugin.routes().get(p.getUniqueId());
                if (r == null) {
                    Msg.send(p, "<gray>GPS выключен. Откройте путеводитель или <white>/gps start <npc>");
                    return true;
                }
                GpsNpc npc = plugin.npcs().get(r.npcId());
                TerrainMap map = plugin.maps().get(r.mapId());
                Msg.send(p, "<green>GPS активен"
                        + "\n<gray>  Цель: <white>" + (npc == null ? "?" : Msg.esc(npc.displayName()))
                        + "\n<gray>  Карта: <white>" + (map == null ? Msg.esc(r.mapId()) : Msg.esc(map.displayName()))
                        + "\n<gray>  Пройдено: <white>" + Math.round(r.progress()) + " м</white>, осталось: <yellow>" + Math.round(r.remaining()) + " м"
                        + "\n<gray>  Маркеров на пути: <white>" + r.markers().size()
                        + (r.markers().isEmpty() ? "" : " <dark_gray>(" + r.markers().get(0).shortId() + " → " + r.markers().get(r.markers().size() - 1).shortId() + ")"));
                return true;
            }
            case "give": {
                if (!perm(sender, "gps.give")) return true;
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        Msg.send(sender, "<red>Игрок <white>" + Msg.esc(args[1]) + "</white> не в сети.");
                        return true;
                    }
                } else {
                    target = player(sender);
                    if (target == null) return true;
                }
                target.getInventory().addItem(plugin.items().guide()).values()
                        .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
                Msg.send(target, "<green>Вы получили <white>GPS-путеводитель</white>. ПКМ — открыть навигатор.");
                if (target != sender) Msg.send(sender, "<green>Путеводитель выдан игроку " + Msg.esc(target.getName()) + ".");
                return true;
            }
            case "reload": {
                if (!perm(sender, "gps.admin.reload")) return true;
                plugin.reloadAll();
                Msg.send(sender, "<green>Конфигурация и данные GPSNavigation перезагружены.");
                return true;
            }
            default:
                Msg.send(sender, "<gray>Команды: <white>/gps</white>, <white>/gps start <npc></white>, <white>/gps stop</white>, <white>/gps status</white>"
                        + (sender.hasPermission("gps.give") ? ", <white>/gps give [игрок]</white>" : "")
                        + (sender.hasPermission("gps.admin.reload") ? ", <white>/gps reload</white>" : ""));
                return true;
        }
    }

    private static final java.util.Map<String, String[]> SUBS = subs(
            "start", p("gps.use"),
            "stop", p("gps.use"),
            "status", p("gps.use"),
            "give", p("gps.give"),
            "reload", p("gps.admin.reload"));

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(allowed(sender, SUBS), args[0]);
        String sub = args[0].toLowerCase();
        if (!can(sender, SUBS, sub)) return List.of();
        if (args.length == 2 && sub.equals("start")) return filter(npcIds(), args[1]);
        if (args.length == 2 && sub.equals("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        return List.of();
    }
}
