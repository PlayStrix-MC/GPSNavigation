package com.gpsnavigation.listener;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.item.SpecialItems;
import com.gpsnavigation.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Обработка специальных предметов.
 * <ul>
 *   <li>Маркеры и зоны создаются ТОЛЬКО в PlayerInteractEvent (основная рука); BlockPlaceEvent лишь
 *   страхует от физической установки — поэтому двойного создания не бывает.</li>
 *   <li>Использование предмета всегда запрещается (useItemInHand = DENY): блок не ставится,
 *   книга знаний не расходуется.</li>
 *   <li>Если другой плагин уже запретил использование предмета — ничего не делаем.</li>
 * </ul>
 */
public final class ItemListener implements Listener {

    private static final long DUPLICATE_WINDOW_MS = 150;

    private final GPSNavigation plugin;

    public ItemListener(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        SpecialItems.Type type = plugin.items().typeOf(event.getItem());
        if (type == null) return;
        Action action = event.getAction();
        if (action == Action.PHYSICAL) return;

        // Уважаем запрет другого плагина (защита регионов и т.п.), но предмет в любом случае не используется ванильно.
        boolean deniedByOthers = event.useItemInHand() == Event.Result.DENY;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        if (deniedByOthers) return;
        // Событие приходит для каждой руки — жезлы работают только из основной, путеводитель из любой
        // (повторное открытие за один клик отсекает throttle).
        if (event.getHand() != EquipmentSlot.HAND && type != SpecialItems.Type.GUIDE) return;

        Player p = event.getPlayer();
        if (!p.hasPermission(type.permission)) {
            Msg.send(p, "<red>У вас нет прав на использование этого предмета.");
            return;
        }
        boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        boolean left = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;

        switch (type) {
            case GUIDE:
                if (right && !plugin.editors().throttle(p, DUPLICATE_WINDOW_MS)) {
                    plugin.gui().openMain(p, 0);
                }
                break;
            case ROAD_WAND:
                if (plugin.editors().throttle(p, DUPLICATE_WINDOW_MS)) return;
                if (right) plugin.roadEditor().handleRight(p, event.getClickedBlock(), event.getBlockFace());
                else if (left) plugin.roadEditor().handleLeft(p);
                break;
            case ZONE_WAND:
                if (plugin.editors().throttle(p, DUPLICATE_WINDOW_MS)) return;
                if (right) plugin.zoneEditor().handleRight(p, event.getClickedBlock(), event.getBlockFace());
                else if (left) plugin.zoneEditor().handleLeft(p);
                break;
            default:
                break;
        }
    }

    /** Страховка: специальные блоки никогда не ставятся в мир (маркер здесь НЕ создаётся). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.items().typeOf(event.getItemInHand()) != null) {
            event.setCancelled(true);
        }
    }

    /** В творческом режиме ЛКМ жезлом не должен ломать блок. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        SpecialItems.Type type = plugin.items().typeOf(event.getPlayer().getInventory().getItemInMainHand());
        if (type == SpecialItems.Type.ROAD_WAND || type == SpecialItems.Type.ZONE_WAND) {
            event.setCancelled(true);
        }
    }

    /** Специальные предметы нельзя использовать в крафте (например, разобрать изумрудный блок). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (plugin.items().typeOf(item) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
