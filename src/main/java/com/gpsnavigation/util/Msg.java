package com.gpsnavigation.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Сообщения в формате MiniMessage. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Десериализация через базовый интерфейс — стабильная сигнатура во всех версиях Adventure. */
    private static final ComponentSerializer<Component, Component, String> PARSER = MM;
    private static String prefix = "<dark_green>[<green>GPS</green>]</dark_green> ";

    private Msg() {
    }

    public static void setPrefix(String value) {
        if (value != null) {
            prefix = value;
        }
    }

    public static Component mm(String text) {
        return PARSER.deserialize(text);
    }

    /** Текст для названий и описаний предметов (без курсива по умолчанию). */
    public static Component item(String text) {
        return PARSER.deserialize("<!italic>" + text);
    }

    /** Экранирует пользовательский текст (имена NPC, карт), чтобы он не интерпретировался как теги. */
    public static String esc(String text) {
        return text == null ? "" : MM.escapeTags(text);
    }

    public static void send(CommandSender to, String text) {
        to.sendMessage(PARSER.deserialize(prefix + text));
    }

    public static void raw(CommandSender to, String text) {
        to.sendMessage(PARSER.deserialize(text));
    }

    public static void actionBar(Player player, String text) {
        player.sendActionBar(PARSER.deserialize(text));
    }

    public static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
