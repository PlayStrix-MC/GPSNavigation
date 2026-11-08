package com.gpsnavigation.data;

import com.gpsnavigation.lib.jumper.interp.Config;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * config.jmc (Jumper). Нет файла — создаётся из встроенного; рядом старый config.yml — его значения
 * переносятся в config.jmc (переписываются только отличающиеся разделы), а yml переименовывается в config.yml.old.
 * Отсутствующие ключи берутся из встроенного config.jmc.
 */
public final class ConfigFile {

    private ConfigFile() {}

    public static ConfigurationSection load(File dataFolder, Supplier<InputStream> bundled, Logger logger) {
        File file = new File(dataFolder, "config.jmc");
        String bundledText = read(bundled, logger);
        YamlConfiguration defaults = parseOrEmpty(bundledText, logger);
        if (!file.exists() && bundledText != null) {
            try {
                Files.createDirectories(dataFolder.toPath());
                Files.writeString(file.toPath(), bundledText, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Не удалось создать config.jmc", e);
                return defaults;
            }
            migrateLegacy(dataFolder, file, defaults, logger);
        }
        YamlConfiguration cfg;
        try {
            cfg = Jmc.load(file.toPath());
        } catch (Exception e) {
            logger.severe("config.jmc: ошибка — " + e.getMessage() + ". Используются значения по умолчанию.");
            return defaults;
        }
        cfg.setDefaults(defaults);
        return cfg;
    }

    private static String read(Supplier<InputStream> bundled, Logger logger) {
        try (InputStream in = bundled.get()) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.severe("Встроенный config.jmc не читается: " + e.getMessage());
            return null;
        }
    }

    private static YamlConfiguration parseOrEmpty(String text, Logger logger) {
        if (text == null) return new YamlConfiguration();
        try {
            return Jmc.parse(text);
        } catch (Exception e) {
            logger.severe("Встроенный config.jmc не разбирается: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private static void migrateLegacy(File dataFolder, File file, YamlConfiguration defaults, Logger logger) {
        File legacy = new File(dataFolder, "config.yml");
        if (!legacy.exists()) return;
        try {
            Map<String, Object> old = Jmc.camelize(YamlConfiguration.loadConfiguration(legacy));
            Object dl = old.remove("displaylib");
            if (dl != null) old.put("displayLib", dl);
            Map<String, Object> defs = Jmc.toPlain(defaults);
            Map<String, Object> changes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : old.entrySet()) {
                Object def = defs.get(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> section) || !(def instanceof Map<?, ?> defSection)) continue;
                // Раздел = значения по умолчанию + изменённые пользователем; неизвестные ключи отбрасываются.
                Map<String, Object> merged = new LinkedHashMap<>();
                boolean differs = false;
                for (Map.Entry<?, ?> d : defSection.entrySet()) {
                    String k = String.valueOf(d.getKey());
                    Object v = section.containsKey(k) ? section.get(k) : d.getValue();
                    if (!sameValue(v, d.getValue())) differs = true;
                    merged.put(k, v);
                }
                if (differs) changes.put(e.getKey(), merged);
            }
            if (!changes.isEmpty()) Config.update(file.toPath(), changes);
            Files.move(legacy.toPath(), new File(dataFolder, "config.yml.old").toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("config.yml перенесён в config.jmc" + (changes.isEmpty() ? "" : " (свои значения в разделах: "
                    + String.join(", ", changes.keySet()) + ")") + "; старый файл — config.yml.old");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Не удалось перенести config.yml в config.jmc", e);
        }
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a == null ? null : String.valueOf(a), b == null ? null : String.valueOf(b));
    }
}
