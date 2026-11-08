package com.gpsnavigation.listener;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.gui.GpsMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** Все клики в меню навигатора отменяются; предметы забрать/положить нельзя. */
public final class GuiListener implements Listener {

    private final GPSNavigation plugin;

    public GuiListener(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof GpsMenu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        // Клик в инвентаре игрока (нижняя часть) — игнорируем, но всё равно отменяем (shift-клик и т.п.).
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;
        plugin.gui().handleClick((Player) event.getWhoClicked(), (GpsMenu) holder, event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GpsMenu) {
            event.setCancelled(true);
        }
    }
}
