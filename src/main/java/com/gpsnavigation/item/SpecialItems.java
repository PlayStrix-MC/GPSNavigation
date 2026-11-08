package com.gpsnavigation.item;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Специальные предметы плагина. Определяются только по PDC-метке, а не по названию,
 * поэтому обычные изумрудные блоки, бедрок и книги знаний ничего не делают.
 */
public final class SpecialItems {

    public enum Type {
        GUIDE("guide", "gps.use"),
        ROAD_WAND("road_wand", "gps.admin.road"),
        ZONE_WAND("zone_wand", "gps.admin.zone");

        public final String id;
        public final String permission;

        Type(String id, String permission) {
            this.id = id;
            this.permission = permission;
        }

        static Type byId(String id) {
            if (id == null) return null;
            for (Type t : values()) {
                if (t.id.equals(id)) return t;
            }
            return null;
        }
    }

    private final GPSNavigation plugin;
    private final NamespacedKey key;

    public SpecialItems(GPSNavigation plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "item");
    }

    public Type typeOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return Type.byId(id);
    }

    public boolean is(ItemStack item, Type type) {
        return typeOf(item) == type;
    }

    public ItemStack guide() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ПКМ — открыть навигатор");
        lore.add("<gray>Выберите карту, затем NPC —");
        lore.add("<gray>указатели поведут вас по дороге.");
        lore.add("");
        lore.add("<dark_gray>Повторный выбор NPC отменяет маршрут");
        ItemStack item = build(Material.KNOWLEDGE_BOOK, "<green><bold>GPS-путеводитель", lore, Type.GUIDE);
        String model = plugin.settings().guideModel;
        if (model != null && !model.isBlank()) {
            NamespacedKey modelKey = NamespacedKey.fromString(model);
            if (modelKey != null) {
                ItemMeta meta = item.getItemMeta();
                meta.setItemModel(modelKey);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    public ItemStack roadWand() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ПКМ по блоку — создать маркер");
        lore.add("<gray>ЛКМ по маркеру (2 раза) — удалить");
        lore.add("<gray>Shift+ПКМ по двум маркерам — связать");
        lore.add("<gray>Shift+ЛКМ по двум маркерам — разорвать связь");
        lore.add("");
        lore.add("<dark_gray>Блок не ставится в мир");
        return build(Material.EMERALD_BLOCK, "<green><bold>Дорожный маркер", lore, Type.ROAD_WAND);
    }

    public ItemStack zoneWand() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>ПКМ по блоку — поставить точку зоны");
        lore.add("<gray>Точки соединяются сами, как маркеры дорог;");
        lore.add("<gray>площадь внутри замкнутого контура — мёртвая зона");
        lore.add("<gray>ЛКМ по точке (2 раза) — удалить");
        lore.add("<gray>Shift+ПКМ / Shift+ЛКМ по двум точкам — связать / разорвать");
        lore.add("");
        lore.add("<dark_gray>Блок не ставится в мир");
        return build(Material.BEDROCK, "<dark_purple><bold>Маркер мёртвой зоны", lore, Type.ZONE_WAND);
    }

    private ItemStack build(Material material, String name, List<String> loreLines, Type type) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Msg.item(name));
        List<Component> lore = new ArrayList<>();
        for (String l : loreLines) lore.add(Msg.item(l));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.id);
        item.setItemMeta(meta);
        return item;
    }
}
