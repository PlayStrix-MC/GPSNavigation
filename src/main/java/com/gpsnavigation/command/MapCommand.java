package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /gpsmap — карты местности. */
public final class MapCommand extends BaseCommand {

    public MapCommand(GPSNavigation plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("select")) {
            // Выбор карты нужен и для редактирования дорог/зон.
            if (!sender.hasPermission("gps.admin.road") && !sender.hasPermission("gps.admin.zone") && !perm(sender, "gps.admin.map")) return true;
            Player p = player(sender);
            if (p == null) return true;
            TerrainMap map = args.length >= 2 ? plugin.maps().get(args[1]) : null;
            if (map == null) {
                Msg.send(p, "<red>Карта не найдена.");
                return true;
            }
            plugin.editors().setEditMap(p, map.id());
            Msg.send(p, "<green>Карта редактирования: <white>" + Msg.esc(map.displayName()) + "</white> <dark_gray>(" + map.id() + ", мир " + Msg.esc(map.world()) + ")");
            return true;
        }
        if (sub.equals("list")) {
            if (!any(sender, "gps.admin.map", "gps.admin.road", "gps.admin.zone", "gps.admin.npc")) {
                perm(sender, "gps.admin.map");
                return true;
            }
            StringBuilder sb = new StringBuilder("<green>Карты местности:");
            if (plugin.maps().all().isEmpty()) sb.append(" <gray>нет");
            for (TerrainMap m : plugin.maps().all()) {
                sb.append("\n<gray>  <white>").append(m.id()).append("</white> «").append(Msg.esc(m.displayName()))
                        .append("» <dark_gray>мир ").append(Msg.esc(m.world())).append("</dark_gray> маркеров: ")
                        .append(plugin.network().ofMap(m.id()).size()).append(", NPC: ").append(plugin.npcs().ofMap(m.id()).size())
                        .append(", зон: ").append(plugin.zones().ofMap(m.id()).size());
            }
            Msg.send(sender, sb.toString());
            return true;
        }
        if (!perm(sender, "gps.admin.map")) return true;
        switch (sub) {
            case "create": {
                Player p = player(sender);
                if (p == null) return true;
                if (args.length < 2 || !args[1].matches("[A-Za-z0-9_\\-]{1,32}")) {
                    Msg.send(p, "<gray>Использование: <white>/gpsmap create <id> [название]</white> <dark_gray>(id: латиница, цифры, _ -)");
                    return true;
                }
                if (plugin.maps().get(args[1]) != null) {
                    Msg.send(p, "<red>Карта с таким ID уже существует.");
                    return true;
                }
                String name = args.length >= 3 ? join(args, 2) : args[1];
                TerrainMap map = plugin.maps().create(args[1], p.getWorld().getName(), name);
                plugin.editors().setEditMap(p, map.id());
                plugin.requestSave();
                Msg.send(p, "<green>Карта <white>" + Msg.esc(name) + "</white> создана в мире " + Msg.esc(map.world()) + " и выбрана для редактирования.");
                return true;
            }
            case "remove": {
                TerrainMap map = args.length >= 2 ? plugin.maps().get(args[1]) : null;
                if (map == null) {
                    Msg.send(sender, "<red>Карта не найдена.");
                    return true;
                }
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    Msg.send(sender, "<yellow>Будут удалены все маркеры, зоны и NPC карты. Подтвердите: <white>/gpsmap remove " + map.id() + " confirm");
                    return true;
                }
                plugin.routes().onMapRemoved(map.id());
                plugin.network().removeMap(map.id());
                plugin.zones().removeMap(map.id());
                plugin.npcs().ofMap(map.id()).forEach(n -> plugin.npcs().remove(n));
                plugin.maps().remove(map.id());
                plugin.requestSave();
                Msg.send(sender, "<green>Карта " + Msg.esc(map.id()) + " и все её данные удалены.");
                return true;
            }
            case "rename":
            case "desc":
            case "icon": {
                TerrainMap map = args.length >= 2 ? plugin.maps().get(args[1]) : null;
                if (map == null || args.length < 3) {
                    Msg.send(sender, "<gray>Использование: <white>/gpsmap " + sub + " <id> <значение>");
                    return true;
                }
                if (sub.equals("rename")) {
                    map.displayName(join(args, 2));
                } else if (sub.equals("desc")) {
                    String text = join(args, 2);
                    List<String> lines = new ArrayList<>();
                    if (!text.equalsIgnoreCase("clear")) {
                        for (String l : text.split("\\|")) lines.add(l.trim());
                    }
                    map.description(lines);
                } else {
                    Material mat = Material.matchMaterial(args[2]);
                    if (mat == null || !mat.isItem() || mat.isAir()) {
                        Msg.send(sender, "<red>Неизвестный предмет.");
                        return true;
                    }
                    map.icon(mat);
                }
                plugin.maps().markDirty();
                plugin.requestSave();
                Msg.send(sender, "<green>Карта " + Msg.esc(map.id()) + " обновлена.");
                return true;
            }
            default:
                Msg.send(sender, "<gray>/gpsmap create <id> [название]</gray> <dark_gray>—</dark_gray> <white>карта в текущем мире"
                        + "\n<gray>/gpsmap select <id></gray> <dark_gray>—</dark_gray> <white>карта для новых маркеров и зон"
                        + "\n<gray>/gpsmap list, /gpsmap remove <id> confirm"
                        + "\n<gray>/gpsmap rename|desc|icon <id> <значение>");
                return true;
        }
    }

    private static final String MAP = "gps.admin.map";
    private static final java.util.Map<String, String[]> SUBS = subs(
            "create", p(MAP),
            "select", p(MAP, "gps.admin.road", "gps.admin.zone"),
            "list", p(MAP, "gps.admin.road", "gps.admin.zone", "gps.admin.npc"),
            "rename", p(MAP),
            "desc", p(MAP),
            "icon", p(MAP),
            "remove", p(MAP));

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(allowed(sender, SUBS), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!can(sender, SUBS, sub)) return List.of();
        if (args.length == 2 && !sub.equals("create") && !sub.equals("list")) return filter(mapIds(), args[1]);
        if (args.length == 3 && sub.equals("remove")) return filter(List.of("confirm"), args[2]);
        if (args.length == 3 && sub.equals("icon")) {
            List<String> mats = new ArrayList<>();
            for (Material m : Material.values()) {
                if (m.isItem() && !m.isAir()) mats.add(m.name().toLowerCase(Locale.ROOT));
            }
            return filter(mats, args[2]);
        }
        return List.of();
    }
}
