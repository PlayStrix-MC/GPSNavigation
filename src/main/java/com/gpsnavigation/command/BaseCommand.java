package com.gpsnavigation.command;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

abstract class BaseCommand implements TabExecutor {

    protected final GPSNavigation plugin;

    BaseCommand(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    protected boolean perm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        Msg.send(sender, "<red>Недостаточно прав (<white>" + permission + "</white>).");
        return false;
    }

    protected Player player(CommandSender sender) {
        if (sender instanceof Player) return (Player) sender;
        Msg.send(sender, "<red>Команда доступна только игроку.");
        return null;
    }

    /** Есть ли у отправителя хотя бы одно из прав. */
    protected static boolean any(CommandSender sender, String... permissions) {
        for (String p : permissions) {
            if (sender.hasPermission(p)) return true;
        }
        return false;
    }

    /**
     * Подкоманды, доступные отправителю: ключ — подкоманда, значение — права (достаточно любого).
     */
    protected static List<String> allowed(CommandSender sender, java.util.Map<String, String[]> subs) {
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String[]> e : subs.entrySet()) {
            if (any(sender, e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Может ли отправитель использовать подкоманду (для подсказок аргументов). */
    protected static boolean can(CommandSender sender, java.util.Map<String, String[]> subs, String sub) {
        String[] perms = subs.get(sub.toLowerCase(Locale.ROOT));
        return perms != null && any(sender, perms);
    }

    protected static java.util.Map<String, String[]> subs(Object... pairs) {
        java.util.Map<String, String[]> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (String[]) pairs[i + 1]);
        return m;
    }

    protected static String[] p(String... perms) {
        return perms;
    }

    protected static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    protected static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    protected List<String> mapIds() {
        List<String> ids = new ArrayList<>();
        plugin.maps().all().forEach(m -> ids.add(m.id()));
        return ids;
    }

    protected List<String> npcIds() {
        List<String> ids = new ArrayList<>();
        plugin.npcs().all().forEach(n -> ids.add(n.id()));
        return ids;
    }

    protected List<String> markerIds() {
        List<String> ids = new ArrayList<>();
        plugin.network().all().forEach(m -> ids.add(m.shortId()));
        return ids;
    }

    protected List<String> zoneIds() {
        List<String> ids = new ArrayList<>();
        plugin.zones().points().forEach(z -> ids.add(z.shortId()));
        return ids;
    }
}
