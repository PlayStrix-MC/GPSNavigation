package com.gpsnavigation.gui;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Меню путеводителя: рамка из серого стекла, внутри только NPC, внизу кнопка «Выйти».
 * Если в мире игрока одна карта, сразу открывается список её NPC; если карт несколько — сначала выбор карты.
 * <p>
 * Клик по NPC строит маршрут и закрывает меню (gui.close-on-select). Повторный клик по NPC с активным
 * маршрутом — отмена. DOUBLE_CLICK обрабатывается, только пока меню открыто, и всегда означает отмену;
 * защита от дребезга не даёт двум событиям одного двойного клика сработать как два переключения.
 */
public final class GuiManager {

    private final GPSNavigation plugin;
    private final Map<UUID, Long> lastAction = new HashMap<>();

    public GuiManager(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    public void forget(UUID player) {
        lastAction.remove(player);
    }

    // ---------- Открытие ----------

    /** Точка входа (путеводитель, /gps). */
    public void openMain(Player p, int page) {
        DisplayLibMenu dl = plugin.displayLibMenu();
        if (dl != null) {
            try {
                if (dl.open(p)) return;
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось открыть экран DisplayLib: " + t + " — открыто меню-инвентарь.");
            }
        }
        List<TerrainMap> maps = plugin.maps().inWorld(p.getWorld().getName());
        if (maps.isEmpty()) maps = new ArrayList<>(plugin.maps().all());
        if (maps.isEmpty()) {
            Msg.send(p, "<gray>Карты местности ещё не настроены.");
            return;
        }
        if (maps.size() == 1) {
            openNpcs(p, maps.get(0).id(), 0);
            return;
        }
        int rows = GpsMenu.rowsFor(maps.size());
        MainMenu menu = new MainMenu(rows, clampPage(page, maps.size(), rows));
        populateMain(menu, maps);
        p.openInventory(menu.getInventory());
    }

    public void openNpcs(Player p, String mapId, int page) {
        TerrainMap map = plugin.maps().get(mapId);
        if (map == null) return;
        List<GpsNpc> npcs = plugin.npcs().ofMap(map.id());
        int rows = GpsMenu.rowsFor(npcs.size());
        NpcMenu menu = new NpcMenu(map.id(), map.displayName(), rows, clampPage(page, npcs.size(), rows));
        populateNpcs(p, menu);
        p.openInventory(menu.getInventory());
    }

    private static int clampPage(int page, int count, int rows) {
        int pages = Math.max(1, (count + GpsMenu.perPage(rows) - 1) / GpsMenu.perPage(rows));
        return Math.max(0, Math.min(page, pages - 1));
    }

    private void populateMain(MainMenu menu, List<TerrainMap> maps) {
        menu.frame();
        int per = GpsMenu.perPage(menu.rows());
        int from = menu.page() * per;
        for (int i = 0; i < per && from + i < maps.size(); i++) {
            TerrainMap map = maps.get(from + i);
            List<String> lore = new ArrayList<>();
            for (String d : map.description()) lore.add("<gray>" + Msg.esc(d));
            lore.add("<dark_gray>NPC: " + plugin.npcs().ofMap(map.id()).size());
            int slot = menu.contentSlot(i);
            menu.getInventory().setItem(slot, GpsMenu.item(map.icon(), "<green>" + Msg.esc(map.displayName()), lore, false));
            menu.targets.put(slot, map.id());
        }
        pageArrows(menu, maps.size());
    }

    private void populateNpcs(Player p, NpcMenu menu) {
        menu.frame();
        List<GpsNpc> npcs = plugin.npcs().ofMap(menu.mapId());
        int per = GpsMenu.perPage(menu.rows());
        int from = menu.page() * per;
        for (int i = 0; i < per && from + i < npcs.size(); i++) {
            GpsNpc npc = npcs.get(from + i);
            boolean active = plugin.routes().isRouteTo(p, npc.id());
            List<String> lore = new ArrayList<>();
            for (String d : npc.description()) lore.add("<gray>" + Msg.esc(d));
            if (active) {
                if (!lore.isEmpty()) lore.add("");
                lore.add("<aqua>● Маршрут активен — нажмите, чтобы отменить");
            }
            int slot = menu.contentSlot(i);
            menu.getInventory().setItem(slot, GpsMenu.item(npc.icon(),
                    (active ? "<aqua>" : "<green>") + Msg.esc(npc.displayName()), lore, active));
            menu.targets.put(slot, npc.id());
        }
        pageArrows(menu, npcs.size());
    }

    /** Стрелки появляются в углах нижней рамки, только если элементов больше, чем помещается. */
    private void pageArrows(GpsMenu menu, int count) {
        int per = GpsMenu.perPage(menu.rows());
        int pages = Math.max(1, (count + per - 1) / per);
        if (menu.page() > 0) {
            menu.getInventory().setItem(menu.prevSlot(), GpsMenu.item(org.bukkit.Material.ARROW, "<white>←", List.of(), false));
        }
        if (menu.page() < pages - 1) {
            menu.getInventory().setItem(menu.nextSlot(), GpsMenu.item(org.bukkit.Material.ARROW, "<white>→", List.of(), false));
        }
    }

    // ---------- Клики ----------

    public void handleClick(Player p, GpsMenu menu, int slot, ClickType click) {
        if (slot < 0 || slot >= menu.rows() * 9) return;
        if (slot == menu.exitSlot()) {
            if (click != ClickType.DOUBLE_CLICK) closeLater(p);
            return;
        }
        if (slot == menu.prevSlot() || slot == menu.nextSlot()) {
            if (click == ClickType.DOUBLE_CLICK || menu.getInventory().getItem(slot) == null
                    || menu.getInventory().getItem(slot).getType() != org.bukkit.Material.ARROW) return;
            int page = menu.page() + (slot == menu.nextSlot() ? 1 : -1);
            if (menu instanceof NpcMenu) openNpcs(p, ((NpcMenu) menu).mapId(), page);
            else openMain(p, page);
            return;
        }
        String target = menu.targetAt(slot);
        if (target == null) return;
        if (menu instanceof MainMenu) {
            if (click != ClickType.DOUBLE_CLICK) openNpcs(p, target, 0);
        } else if (menu instanceof NpcMenu) {
            handleNpcClick(p, (NpcMenu) menu, target, click);
        }
    }

    private void handleNpcClick(Player p, NpcMenu menu, String npcId, ClickType click) {
        GpsNpc npc = plugin.npcs().get(npcId);
        if (npc == null) {
            refresh(p, menu);
            return;
        }
        boolean active = plugin.routes().isRouteTo(p, npc.id());
        long now = System.currentTimeMillis();

        if (click == ClickType.DOUBLE_CLICK) {
            if (active) {
                plugin.routes().stop(p.getUniqueId(), true);
                lastAction.put(p.getUniqueId(), now);
                refresh(p, menu);
            }
            return;
        }
        if (!click.isLeftClick() && !click.isRightClick()) return;
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < plugin.settings().clickDebounceMs) return;
        lastAction.put(p.getUniqueId(), now);

        if (active) {
            plugin.routes().stop(p.getUniqueId(), true);
            refresh(p, menu);
            return;
        }
        boolean started = plugin.routes().start(p, npc);
        if (started && plugin.settings().closeOnSelect) closeLater(p);
        else refresh(p, menu);
    }

    /** Обновляет содержимое открытого меню на следующем тике, не переоткрывая окно. */
    private void refresh(Player p, GpsMenu menu) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != menu) return;
            if (menu instanceof NpcMenu) {
                if (plugin.maps().get(((NpcMenu) menu).mapId()) == null) p.closeInventory();
                else populateNpcs(p, (NpcMenu) menu);
            }
        });
    }

    /** Закрытие инвентаря внутри InventoryClickEvent небезопасно — переносим на следующий тик. */
    private void closeLater(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof GpsMenu) {
                p.closeInventory();
            }
        });
    }
}
