package com.gpsnavigation.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Записывает YAML-файлы в отдельном потоке, по очереди, через временный файл. */
public final class DataWriter {

    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPSNavigation-IO");
        t.setDaemon(true);
        return t;
    });

    public DataWriter(Logger logger) {
        this.logger = logger;
    }

    public void writeAsync(File file, String content) {
        executor.submit(() -> write(file, content));
    }

    /** Синхронная запись через ту же очередь — порядок с асинхронными записями сохраняется. */
    public void writeSync(File file, String content) {
        try {
            executor.submit(() -> write(file, content)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Не удалось сохранить " + file.getName(), e);
        }
    }

    private void write(File file, String content) {
        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Не удалось сохранить " + file.getName(), e);
        }
    }

    /** Дожидается завершения всех отложенных записей. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Запись данных GPSNavigation не завершилась за 10 секунд");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
