package com.gpsnavigation.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.Map;

/**
 * Скрывает команды плагина из списка подсказок клиента, если у игрока нет ни одного подходящего права.
 * Подкоманды фильтруются в onTabComplete каждой команды.
 */
public final class CommandVisibilityListener implements Listener {

    private static final Map<String, String[]> COMMANDS = Map.of(
            "gps", new String[]{"gps.use", "gps.give", "gps.admin.reload"},
            "road", new String[]{"gps.admin.road", "gps.admin.debug"},
            "gpszone", new String[]{"gps.admin.zone"},
            "gpsnpc", new String[]{"gps.admin.npc"},
            "gpsmap", new String[]{"gps.admin.map", "gps.admin.road", "gps.admin.zone", "gps.admin.npc"});

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player p = event.getPlayer();
        for (Map.Entry<String, String[]> e : COMMANDS.entrySet()) {
            boolean ok = false;
            for (String perm : e.getValue()) {
                if (p.hasPermission(perm)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                event.getCommands().remove(e.getKey());
                event.getCommands().remove("gpsnavigation:" + e.getKey());
            }
        }
    }
}
