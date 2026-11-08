package com.gpsnavigation.listener;

import com.gpsnavigation.GPSNavigation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

/** Очистка состояния игроков и защита визуальных сущностей. */
public final class PlayerListener implements Listener {

    private final GPSNavigation plugin;

    public PlayerListener(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.routes().stop(id, false);
        plugin.debug().remove(id);
        plugin.editors().clear(id);
        plugin.gui().forget(id);
    }

    /** Клиент очищает все сущности при возрождении и смене мира — пересоздаём указатели на следующем тике. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        org.bukkit.entity.Player p = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> plugin.routes().resetClient(p));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.routes().resetClient(event.getPlayer());
    }

    /** Снаряд-указатель не должен никого задевать и накладывать левитацию. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (plugin.visuals().isVisual(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.visuals().isVisual(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
