package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.RoadMarker;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /gpsnpc — точки назначения (NPC). */
public final class NpcCommand extends BaseCommand {

    public NpcCommand(GPSNavigation plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!perm(sender, "gps.admin.npc")) return true;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": {
                Player p = player(sender);
                if (p == null) return true;
                if (args.length < 3) {
                    Msg.send(p, "<gray>Использование: <white>/gpsnpc create <id> <карта> [название]");
                    return true;
                }
                if (plugin.npcs().get(args[1]) != null) {
                    Msg.send(p, "<red>NPC с таким ID уже существует.");
                    return true;
                }
                if (!args[1].matches("[A-Za-z0-9_\\-]{1,32}")) {
                    Msg.send(p, "<red>ID: латиница, цифры, _ и -, до 32 символов.");
                    return true;
                }
                TerrainMap map = plugin.maps().get(args[2]);
                if (map == null) {
                    Msg.send(p, "<red>Карта не найдена.");
                    return true;
                }
                if (!map.world().equals(p.getWorld().getName())) {
                    Msg.send(p, "<red>Карта «" + Msg.esc(map.id()) + "» находится в мире " + Msg.esc(map.world()) + ".");
                    return true;
                }
                Location l = p.getLocation();
                String name = args.length >= 4 ? join(args, 3) : args[1];
                GpsNpc npc = plugin.npcs().create(args[1], map.id(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), name);
                RoadMarker m = plugin.roads().relinkNpc(npc);
                plugin.requestSave();
                Msg.send(p, "<green>NPC <white>" + Msg.esc(name) + "</white> создан на карте " + Msg.esc(map.id()) + ". "
                        + (m == null ? "<red>Рядом нет маркера (радиус " + Msg.fmt(plugin.settings().npcLinkRadius)
                        + ") — NPC не привязан, GPS к нему не построит маршрут."
                        : "<gray>Привязан к маркеру <white>" + m.shortId()));
                return true;
            }
            case "list": {
                String mapId = args.length >= 2 ? args[1] : null;
                StringBuilder sb = new StringBuilder("<green>NPC:");
                int count = 0;
                for (GpsNpc n : plugin.npcs().all()) {
                    if (mapId != null && !n.mapId().equalsIgnoreCase(mapId)) continue;
                    count++;
                    RoadMarker m = plugin.network().get(n.linkedMarker());
                    sb.append("\n<gray>  <white>").append(n.id()).append("</white> «").append(Msg.esc(n.displayName()))
                            .append("» <dark_gray>[").append(Msg.esc(n.mapId())).append("]</dark_gray> ")
                            .append(m == null ? "<red>не привязан" : "<gray>→ " + m.shortId());
                }
                if (count == 0) sb.append(" <gray>нет");
                Msg.send(sender, sb.toString());
                return true;
            }
            default:
                break;
        }
        if (List.of("remove", "move", "rename", "desc", "icon", "relink", "info", "bind", "unbind").contains(sub)) {
            if (args.length < 2) {
                Msg.send(sender, "<gray>Укажите ID NPC.");
                return true;
            }
            GpsNpc npc = plugin.npcs().get(args[1]);
            if (npc == null) {
                Msg.send(sender, "<red>NPC не найден.");
                return true;
            }
            switch (sub) {
                case "remove":
                    plugin.npcs().remove(npc);
                    plugin.routes().onNpcRemoved(npc);
                    plugin.requestSave();
                    Msg.send(sender, "<green>NPC " + Msg.esc(npc.id()) + " удалён, его привязка к дороге снята.");
                    return true;
                case "move": {
                    Player p = player(sender);
                    if (p == null) return true;
                    TerrainMap map = plugin.maps().get(npc.mapId());
                    if (map != null && !map.world().equals(p.getWorld().getName())) {
                        Msg.send(p, "<red>Карта NPC находится в другом мире.");
                        return true;
                    }
                    Location l = p.getLocation();
                    npc.moveTo(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
                    plugin.npcs().markDirty();
                    RoadMarker m = plugin.roads().relinkNpc(npc);
                    plugin.roads().changed(npc.mapId());
                    Msg.send(p, "<green>NPC перемещён. " + (m == null ? "<red>Подходящего маркера рядом нет." : "<gray>Привязка: " + m.shortId()));
                    return true;
                }
                case "rename":
                    if (args.length < 3) {
                        Msg.send(sender, "<gray>Использование: <white>/gpsnpc rename <id> <название>");
                        return true;
                    }
                    npc.displayName(join(args, 2));
                    break;
                case "desc": {
                    if (args.length < 3) {
                        Msg.send(sender, "<gray>Использование: <white>/gpsnpc desc <id> <текст | clear></white> <dark_gray>(строки разделяются символом |)");
                        return true;
                    }
                    String text = join(args, 2);
                    List<String> lines = new ArrayList<>();
                    if (!text.equalsIgnoreCase("clear")) {
                        for (String line : text.split("\\|")) lines.add(line.trim());
                    }
                    npc.description(lines);
                    break;
                }
                case "icon": {
                    Material mat = args.length >= 3 ? Material.matchMaterial(args[2]) : null;
                    if (mat == null || !mat.isItem() || mat.isAir()) {
                        Msg.send(sender, "<red>Неизвестный предмет.");
                        return true;
                    }
                    npc.icon(mat);
                    break;
                }
                case "bind": {
                    Player p = player(sender);
                    if (p == null) return true;
                    org.bukkit.entity.Entity target = p.getTargetEntity(8);
                    if (target == null || target instanceof Player && !target.hasMetadata("NPC")) {
                        Msg.send(p, "<red>Посмотрите на сущность NPC (до 8 блоков).");
                        return true;
                    }
                    npc.entityUuid(target.getUniqueId());
                    Msg.send(p, "<green>К NPC " + Msg.esc(npc.id()) + " привязана сущность <white>" + target.getType().name()
                            + "</white> — она будет светиться у игроков с маршрутом к нему.");
                    break;
                }
                case "unbind":
                    npc.entityUuid(null);
                    break;
                case "relink": {
                    RoadMarker m = plugin.roads().relinkNpc(npc);
                    plugin.roads().changed(npc.mapId());
                    Msg.send(sender, m == null ? "<red>Подходящего маркера рядом нет — NPC не привязан."
                            : "<green>NPC привязан к маркеру " + m.shortId());
                    return true;
                }
                default: {
                    RoadMarker m = plugin.network().get(npc.linkedMarker());
                    Msg.send(sender, "<green>NPC <white><bold>" + Msg.esc(npc.id()) + "</bold></white> «" + Msg.esc(npc.displayName()) + "»"
                            + "\n<gray>  Карта: <white>" + Msg.esc(npc.mapId()) + "</white>, мир <white>" + Msg.esc(npc.world())
                            + "\n<gray>  Координаты: <white>" + npc.coords()
                            + "\n<gray>  Маркер: " + (m == null ? "<red>не привязан" : "<white>" + m.shortId())
                            + "\n<gray>  Сущность для свечения: <white>" + (npc.entityUuid() == null ? "авто (ближайшая к точке NPC)" : npc.entityUuid().toString())
                            + "\n<gray>  Описание: <white>" + (npc.description().isEmpty() ? "—" : Msg.esc(String.join(" / ", npc.description()))));
                    return true;
                }
            }
            plugin.npcs().markDirty();
            plugin.requestSave();
            Msg.send(sender, "<green>NPC " + Msg.esc(npc.id()) + " обновлён.");
            return true;
        }
        Msg.send(sender, "<gray>/gpsnpc create <id> <карта> [название]</gray> <dark_gray>—</dark_gray> <white>NPC в вашей позиции"
                + "\n<gray>/gpsnpc remove|move|relink|info <id>"
                + "\n<gray>/gpsnpc rename <id> <название>, /gpsnpc desc <id> <текст|clear>, /gpsnpc icon <id> <предмет>"
                + "\n<gray>/gpsnpc bind <id></gray> <dark_gray>—</dark_gray> <white>привязать сущность, на которую смотрите (для свечения); <gray>/gpsnpc unbind <id>"
                + "\n<gray>/gpsnpc list [карта]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("gps.admin.npc")) return List.of();
        if (args.length == 1) {
            return filter(List.of("create", "remove", "move", "rename", "desc", "icon", "relink", "list", "info", "bind", "unbind"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("list")) return filter(mapIds(), args[1]);
        if (args.length == 3 && sub.equals("create")) return filter(mapIds(), args[2]);
        if (args.length == 2 && !sub.equals("create")) return filter(npcIds(), args[1]);
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
