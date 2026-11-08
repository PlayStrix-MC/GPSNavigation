package com.gpsnavigation.editor;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.util.Msg;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Состояние редактирования каждого администратора: выбранная карта, выделения, подтверждения. */
public final class EditorSessions {

    public static final class Selection {
        public final UUID marker;
        public final boolean unlink;
        /** true — выбрана точка мёртвой зоны, false — дорожный маркер. */
        public final boolean zone;

        Selection(UUID marker, boolean unlink, boolean zone) {
            this.marker = marker;
            this.unlink = unlink;
            this.zone = zone;
        }
    }

    private final GPSNavigation plugin;
    private final Map<UUID, String> editMap = new HashMap<>();
    private final Map<UUID, Selection> selection = new HashMap<>();
    private final Map<UUID, String> pendingConfirm = new HashMap<>();
    private final Map<UUID, Long> pendingConfirmTime = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public EditorSessions(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    // ---------- Карта редактирования ----------

    public void setEditMap(Player p, String mapId) {
        editMap.put(p.getUniqueId(), mapId);
    }

    /**
     * Карта, в которую админ добавляет маркеры/зоны: выбранная через /gpsmap select (если она в текущем мире),
     * иначе единственная карта текущего мира. Если определить нельзя — сообщает об этом и возвращает null.
     */
    public TerrainMap resolveMap(Player p) {
        String world = p.getWorld().getName();
        TerrainMap selected = plugin.maps().get(editMap.get(p.getUniqueId()));
        if (selected != null && selected.world().equals(world)) return selected;
        List<TerrainMap> maps = plugin.maps().inWorld(world);
        if (maps.size() == 1) return maps.get(0);
        if (maps.isEmpty()) {
            Msg.send(p, "<red>В этом мире нет карт местности. Создайте: <white>/gpsmap create <id> [название]");
        } else {
            Msg.send(p, "<red>В этом мире несколько карт. Выберите карту: <white>/gpsmap select <id>");
        }
        return null;
    }

    public String selectedMapId(Player p) {
        return editMap.get(p.getUniqueId());
    }

    // ---------- Выделение маркера для связи ----------

    public Selection selection(Player p) {
        return selection.get(p.getUniqueId());
    }

    public void select(Player p, UUID marker, boolean unlink, boolean zone) {
        selection.put(p.getUniqueId(), new Selection(marker, unlink, zone));
    }

    public void clearSelection(Player p) {
        selection.remove(p.getUniqueId());
    }

    public void forgetMarker(UUID marker) {
        selection.values().removeIf(s -> s.marker.equals(marker));
    }

    // ---------- Подтверждения и защита от двойных событий ----------

    /** true, если это повторное действие с тем же ключом в пределах окна подтверждения. */
    public boolean confirm(Player p, String key) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        String prev = pendingConfirm.get(id);
        Long time = pendingConfirmTime.get(id);
        if (key.equals(prev) && time != null && now - time <= plugin.settings().deleteConfirmMs) {
            pendingConfirm.remove(id);
            pendingConfirmTime.remove(id);
            return true;
        }
        pendingConfirm.put(id, key);
        pendingConfirmTime.put(id, now);
        return false;
    }

    /** Отсекает дублирующиеся события взаимодействия (клиент может прислать два пакета за один клик). */
    public boolean throttle(Player p, long ms) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < ms) return true;
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    public void clear(UUID player) {
        selection.remove(player);
        pendingConfirm.remove(player);
        pendingConfirmTime.remove(player);
        lastUse.remove(player);
    }
}
