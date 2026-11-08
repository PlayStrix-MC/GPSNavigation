package com.gpsnavigation.route;

import com.gpsnavigation.GPSNavigation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import padej.displayLib.pack.ContentPacks;
import padej.displayLib.particles.ParticleEffect;
import padej.displayLib.particles.ParticleEmission;
import padej.displayLib.particles.Particles;
import padej.displayLib.particles.Range;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Визуализация маршрута на DisplayLib (display-частицы пакетами, только для игрока с маршрутом).
 * Класс загружается только если плагин DisplayLib установлен и включён (softdepend).
 * Эффекты лежат в plugins/GPSNavigation/displaylib/particles/*.jmc и правятся без пересборки (/displaylib reload).
 */
public final class DisplayLibVisuals {

    public static final String POINTER = "gps_pointer";
    public static final String TRAIL = "gps_trail";
    public static final String ENTRANCE = "gps_entrance";
    public static final String TARGET = "gps_target";
    public static final String ARRIVE = "gps_arrive";
    private static final String[] EFFECTS = {POINTER, TRAIL, ENTRANCE, TARGET, ARRIVE};

    private final GPSNavigation plugin;

    public DisplayLibVisuals(GPSNavigation plugin) {
        this.plugin = plugin;
        File root = new File(plugin.getDataFolder(), "displaylib");
        for (String id : EFFECTS) {
            if (!new File(root, "particles/" + id + ".jmc").exists()) {
                plugin.saveResource("displaylib/particles/" + id + ".jmc", false);
            }
        }
        updateScreen(new File(root, "screens/gps_navigator.jmc"));
        ContentPacks.register(plugin, root.toPath());
    }

    /**
     * Экран меню: если файла нет или в нём старая версия вёрстки (строка "// layout-version: N"),
     * кладём новый, а прежний сохраняем рядом как .old.
     */
    private void updateScreen(File file) {
        String resource = "displaylib/screens/gps_navigator.jmc";
        String bundled = firstLine(plugin.getResource(resource));
        if (file.exists()) {
            String current;
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                current = firstLine(in);
            } catch (java.io.IOException e) {
                current = null;
            }
            if (bundled == null || bundled.equals(current)) return;
            File old = new File(file.getParentFile(), file.getName() + ".old");
            if (old.exists()) old.delete();
            if (!file.renameTo(old)) file.delete();
            plugin.getLogger().info("Вёрстка экрана меню обновлена (прежняя сохранена как " + old.getName() + ").");
        }
        plugin.saveResource(resource, false);
    }

    private static String firstLine(java.io.InputStream in) {
        if (in == null) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Загружены ли эффекты маршрута (если файл сломан — используется запасной режим). */
    public boolean ready() {
        return Particles.effect(POINTER) != null;
    }

    public void shutdown() {
        ContentPacks.unregister(plugin);
    }

    /**
     * Узел маршрута: светящееся Око Края (или фонарь у входа) + поток искр к следующему узлу.
     *
     * @param next     точка следующего узла (или NPC) — туда летят искры; null — без потока
     */
    public PointerHandle pointer(Player viewer, Location at, boolean entrance, Vector next) {
        List<ParticleEmission> parts = new ArrayList<>();
        ParticleEmission orb = Particles.play(entrance ? ENTRANCE : POINTER, at, viewer);
        if (orb == null) return null;
        parts.add(orb);
        if (next != null && plugin.settings().displayLibTrail) {
            ParticleEmission trail = trail(viewer, at, next);
            if (trail != null) parts.add(trail);
        }
        return new Handle(orb, parts);
    }

    /** Поток искр от точки at к точке to: направление и скорость подбираются, чтобы искра долетала до цели. */
    private ParticleEmission trail(Player viewer, Location at, Vector to) {
        ParticleEffect base = Particles.effect(TRAIL);
        if (base == null) return null;
        Vector dir = to.clone().subtract(at.toVector());
        double dist = dir.length();
        if (dist < 0.3) return null;
        dir.multiply(1 / dist);
        ParticleEffect.Motion m = base.motion();
        double life = (m.lifetime().min() + m.lifetime().max()) / 2;
        ParticleEffect.Motion motion = new ParticleEffect.Motion(m.lifetime(), Range.of(dist / life),
                new double[]{dir.getX(), dir.getY(), dir.getZ()}, m.spread(), 0, 0,
                false, m.bounce(), m.friction(), 0, false, 0);
        ParticleEffect directed = new ParticleEffect(base.id(), base.viewers(), base.keyframeTicks(),
                base.render(), base.emitter(), motion, base.animation());
        return Particles.play(directed, at, viewer);
    }

    /** Подсветка цели (вокруг NPC). */
    public PointerHandle target(Player viewer, Location at) {
        ParticleEmission e = Particles.play(TARGET, at, viewer);
        return e == null ? null : new Handle(null, List.of(e));
    }

    public void arrive(Player viewer, Location at) {
        Particles.play(ARRIVE, at, viewer);
    }

    private static final class Handle implements PointerHandle {
        private final ParticleEmission main;
        private final List<ParticleEmission> parts;
        private final long created = System.currentTimeMillis();
        private boolean removed;

        Handle(ParticleEmission main, List<ParticleEmission> parts) {
            this.main = main;
            this.parts = parts;
        }

        @Override
        public boolean isValid() {
            // Узел живёт lifetime тиков; когда он догорел — пересоздаётся. Если DisplayLib не смог его показать
            // (лимит частиц), повторная попытка — не чаще раза в 2 секунды.
            if (removed) return false;
            if (main == null || System.currentTimeMillis() - created < 2000) return true;
            return !main.isDone();
        }

        @Override
        public void remove() {
            if (removed) return;
            removed = true;
            for (ParticleEmission e : parts) e.cancel();
        }

        @Override
        public void forget() {
            remove();
        }
    }
}
