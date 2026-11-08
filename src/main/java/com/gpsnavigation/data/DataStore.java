package com.gpsnavigation.data;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранилище, которое целиком сохраняется в один файл Jumper (*.jmc).
 * Если *.jmc ещё нет, но есть старый *.yml — данные переносятся, а yml переименовывается в *.yml.old.
 */
public abstract class DataStore {

    protected final File file;
    private final File legacy;
    private final String header;
    private boolean dirty;

    /** @param baseName имя файла без расширения, например "markers" */
    protected DataStore(File dataFolder, String baseName, String header) {
        this.file = new File(dataFolder, baseName + ".jmc");
        this.legacy = new File(dataFolder, baseName + ".yml");
        this.header = header;
    }

    public File file() {
        return file;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void load(Logger logger) {
        YamlConfiguration data = new YamlConfiguration();
        boolean migrated = false;
        if (file.exists()) {
            try {
                data = Jmc.load(file.toPath());
            } catch (Exception e) {
                // Файл с ошибкой не перезаписываем: сохраняем копию, чтобы правку можно было восстановить.
                logger.log(Level.SEVERE, file.getName() + ": ошибка разбора — " + e.getMessage()
                        + ". Файл скопирован в " + file.getName() + ".broken", e);
                copyBroken();
            }
        } else if (legacy.exists()) {
            data = Jmc.fromMap(Jmc.camelize(YamlConfiguration.loadConfiguration(legacy)));
            migrated = true;
        }
        read(data);
        dirty = false;
        if (migrated) {
            try {
                Files.createDirectories(file.toPath().getParent());
                Files.writeString(file.toPath(), snapshot(), StandardCharsets.UTF_8);
                Files.move(legacy.toPath(), new File(legacy.getPath() + ".old").toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info(legacy.getName() + " перенесён в " + file.getName() + " (старый файл: " + legacy.getName() + ".old)");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Не удалось перенести " + legacy.getName() + " в " + file.getName(), e);
                dirty = true;
            }
        }
    }

    private void copyBroken() {
        try {
            Files.copy(file.toPath(), new File(file.getPath() + ".broken").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    /** Снимок данных в виде текста *.jmc (вызывается в основном потоке). */
    public String snapshot() {
        YamlConfiguration data = new YamlConfiguration();
        write(data);
        return Jmc.format(data, header);
    }

    protected abstract void read(YamlConfiguration data);

    protected abstract void write(YamlConfiguration data);
}
