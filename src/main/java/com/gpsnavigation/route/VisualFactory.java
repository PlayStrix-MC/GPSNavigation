package com.gpsnavigation.route;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Создаёт персональные визуальные объекты: указатели маршрута (пакетами) и подписи для админов.
 * <ul>
 *   <li>невидимы по умолчанию — показываются только одному игроку через {@code Player#showEntity};</li>
 *   <li>не сохраняются в мир ({@code setPersistent(false)}) и удаляются плагином при уходе из области/выходе игрока;</li>
 *   <li>помечены PDC-меткой, чтобы гарантированно убирать остатки после сбоев.</li>
 * </ul>
 */
public final class VisualFactory {

    private final GPSNavigation plugin;
    private final NamespacedKey key;

    public VisualFactory(GPSNavigation plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "visual");
    }

    public boolean isVisual(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void prepare(Entity e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.setSilent(true);
        e.setInvulnerable(true);
        e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Указатель маршрута, видимый только viewer.
     * Основной режим — пакеты (pointer.mode: PACKET): на сервере ничего не создаётся, указатель есть только у клиента.
     * Запасной режим — скрытая от остальных несохраняемая сущность.
     */
    public PointerHandle spawnPointer(Player viewer, Location loc, boolean highlight) {
        return spawnPointer(viewer, loc, highlight, null);
    }

    /**
     * @param next следующая точка маршрута — для DisplayLib туда летит поток искр (может быть null)
     */
    public PointerHandle spawnPointer(Player viewer, Location loc, boolean highlight, Vector next) {
        World w = loc.getWorld();
        if (w == null) return null;
        DisplayLibVisuals dl = plugin.displayLib();
        if (dl != null && dl.ready()) {
            PointerHandle h = dl.pointer(viewer, loc, highlight, next);
            if (h != null) return h;
        }
        Settings s = plugin.settings();
        boolean glow = s.pointerGlowing || highlight;
        PacketBridge bridge = plugin.packets();
        if (s.pointerMode == Settings.PointerMode.PACKET && bridge.isAvailable()) {
            int id = bridge.spawnBullet(viewer, loc.getX(), loc.getY(), loc.getZ(), glow);
            if (id >= 0) {
                return new PointerHandle() {
                    private boolean removed;

                    @Override
                    public boolean isValid() {
                        return !removed;
                    }

                    @Override
                    public void remove() {
                        if (removed) return;
                        removed = true;
                        bridge.remove(viewer, id);
                    }

                    @Override
                    public void forget() {
                        removed = true;
                    }
                };
            }
        }
        Entity entity = spawnPointerEntity(viewer, loc, glow, highlight);
        if (entity == null) return null;
        return new PointerHandle() {
            @Override
            public boolean isValid() {
                return entity.isValid();
            }

            @Override
            public void remove() {
                VisualFactory.this.remove(entity);
            }
        };
    }

    private Entity spawnPointerEntity(Player viewer, Location loc, boolean glow, boolean highlight) {
        World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return null;
        Settings s = plugin.settings();
        Entity entity;
        if (s.pointerEntity == Settings.PointerEntity.SHULKER_BULLET && w.getDifficulty() != Difficulty.PEACEFUL) {
            entity = w.spawn(loc, ShulkerBullet.class, b -> {
                prepare(b);
                b.setGravity(false);
                b.setVelocity(new Vector(0, 0, 0));
                b.setTarget(null);
                b.setGlowing(glow);
            });
        } else {
            entity = w.spawn(loc, ItemDisplay.class, d -> {
                prepare(d);
                d.setItemStack(new ItemStack(s.pointerItem));
                d.setBillboard(Display.Billboard.CENTER);
                d.setGlowing(glow);
                if (highlight) d.setGlowColorOverride(Color.AQUA);
            });
        }
        viewer.showEntity(plugin, entity);
        return entity;
    }

    /** Текстовая подпись, видимая только viewer. */
    public TextDisplay spawnLabel(Player viewer, Location loc, Component text) {
        World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return null;
        TextDisplay label = w.spawn(loc, TextDisplay.class, t -> {
            prepare(t);
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);
            t.setShadowed(true);
        });
        viewer.showEntity(plugin, label);
        return label;
    }

    public void remove(Entity e) {
        if (e != null && e.isValid()) e.remove();
    }

    /** Удаляет возможные остатки визуальных сущностей во всех загруженных мирах. */
    public int cleanupStale() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isVisual(e)) {
                    e.remove();
                    removed++;
                }
            }
        }
        return removed;
    }
}
