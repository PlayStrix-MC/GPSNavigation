package com.gpsnavigation.data;

import com.gpsnavigation.lib.jumper.interp.Config;
import com.gpsnavigation.lib.jumper.runtime.JTable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Мост между файлами Jumper (*.jmc) и деревом ConfigurationSection, с которым работает код плагина.
 * Чтение: jmc → таблицы Jumper → секции. Запись: секции → Map/List → {@link Config#format}.
 */
public final class Jmc {

    private static final Pattern UUID_KEY =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Jmc() {}

    /** Читает *.jmc в секцию. */
    public static YamlConfiguration load(Path file) throws IOException {
        return fromValue(Config.load(file));
    }

    /** Разбирает текст *.jmc в секцию. */
    public static YamlConfiguration parse(String source) {
        return fromValue(Config.parse(source));
    }

    private static YamlConfiguration fromValue(Object value) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (value instanceof JTable t) {
            fill(yaml, t);
        } else if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) put(yaml, String.valueOf(e.getKey()), e.getValue());
        } else if (value != null) {
            throw new IllegalArgumentException("ожидалась таблица на верхнем уровне, а не " + value.getClass().getSimpleName());
        }
        return yaml;
    }

    private static void fill(ConfigurationSection target, JTable table) {
        for (Object key : table.keys()) put(target, String.valueOf(key), table.get(key));
    }

    private static void put(ConfigurationSection target, String key, Object value) {
        if (value instanceof JTable t) {
            fill(target.createSection(key), t);
        } else if (value instanceof Map<?, ?> m) {
            ConfigurationSection s = target.createSection(key);
            for (Map.Entry<?, ?> e : m.entrySet()) put(s, String.valueOf(e.getKey()), e.getValue());
        } else {
            target.set(key, plainValue(value));
        }
    }

    private static Object plainValue(Object v) {
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(plainValue(o));
            return out;
        }
        if (v instanceof JTable t) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Object k : t.keys()) m.put(String.valueOf(k), plainValue(t.get(k)));
            return m;
        }
        return v;
    }

    /** Секция → упорядоченная Map для {@link Config#format}. Пустые значения (null) пропускаются. */
    public static Map<String, Object> toPlain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            if (v == null) continue;
            out.put(key, toPlainValue(v));
        }
        return out;
    }

    private static Object toPlainValue(Object v) {
        if (v instanceof ConfigurationSection s) return toPlain(s);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) out.put(String.valueOf(e.getKey()), toPlainValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(toPlainValue(o));
            return out;
        }
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Float f) return f.doubleValue();
        if (v instanceof Short || v instanceof Byte) return ((Number) v).intValue();
        return v;
    }

    public static String format(ConfigurationSection section, String header) {
        return Config.format(toPlain(section), header);
    }

    /**
     * Для миграции старых YAML: ключи вида "link-radius" → "linkRadius" (UUID-ключи не трогаются).
     * Возвращает новое дерево-Map.
     */
    public static Map<String, Object> camelize(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            if (v == null) continue;
            out.put(camel(key), v instanceof ConfigurationSection s ? camelize(s) : toPlainValue(v));
        }
        return out;
    }

    public static String camel(String key) {
        if (key.indexOf('-') < 0 || UUID_KEY.matcher(key).matches()) return key;
        StringBuilder sb = new StringBuilder(key.length());
        boolean up = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-') {
                up = sb.length() > 0;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    /** Map → секция (для дерева после {@link #camelize}). */
    public static YamlConfiguration fromMap(Map<String, Object> map) {
        return fromValue(map);
    }
}
