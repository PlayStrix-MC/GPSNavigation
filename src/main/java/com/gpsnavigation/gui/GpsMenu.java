package com.gpsnavigation.gui;

import com.gpsnavigation.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Меню навигатора: рамка из серого стекла, внутри — элементы (карты или NPC),
 * в центре нижнего ряда — кнопка «Выйти». Размер подбирается под количество элементов.
 */
public abstract class GpsMenu implements InventoryHolder {

    /** Элементов на странице в одном ряду (внутри рамки). */
    public static final int ROW_ITEMS = 7;
    public static final int MAX_ROWS = 6;

    protected final Inventory inventory;
    protected final int page;
    protected final int rows;
    protected final Map<Integer, String> targets = new HashMap<>();

    protected GpsMenu(Component title, int rows, int page) {
        this.rows = rows;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** Сколько рядов нужно для count элементов (рамка сверху и снизу + ряды содержимого). */
    public static int rowsFor(int count) {
        int contentRows = Math.max(1, (count + ROW_ITEMS - 1) / ROW_ITEMS);
        return Math.min(MAX_ROWS, Math.max(3, contentRows + 2));
    }

    public static int perPage(int rows) {
        return (rows - 2) * ROW_ITEMS;
    }

    /** Слот i-го элемента внутри рамки. */
    public int contentSlot(int i) {
        int row = 1 + i / ROW_ITEMS;
        int col = 1 + i % ROW_ITEMS;
        return row * 9 + col;
    }

    public int exitSlot() { return (rows - 1) * 9 + 4; }
    public int prevSlot() { return (rows - 1) * 9; }
    public int nextSlot() { return (rows - 1) * 9 + 8; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int page() {
        return page;
    }

    public int rows() {
        return rows;
    }

    public String targetAt(int slot) {
        return targets.get(slot);
    }

    public static ItemStack item(Material material, String name, List<String> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Msg.item(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(Msg.item(l));
        meta.lore(lines);
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    /** Очищает меню и рисует рамку из серого стекла и кнопку «Выйти». */
    protected void frame() {
        inventory.clear();
        targets.clear();
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        int size = rows * 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) inventory.setItem(i, pane);
        }
        inventory.setItem(exitSlot(), item(Material.BARRIER, "<red>Выйти", List.of(), false));
    }
}
