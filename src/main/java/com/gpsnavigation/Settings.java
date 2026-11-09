package com.gpsnavigation;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** Кэш значений config.jmc. */
public final class Settings {

    public enum PointerEntity { SHULKER_BULLET, ITEM_DISPLAY }

    public enum PointerMode { PACKET, ENTITY }

    public final String prefix;
    public final double linkRadius;
    public final boolean lineOfSight;
    public final double maxHeightDifference;
    public final double manualLinkRadius;
    public final double npcLinkRadius;
    public final double pickDistance;
    public final long deleteConfirmMs;
    public final double zoneLinkRadius;
    public final double pointerSpacing;
    public final double pointerMaxDistance;
    public final int routeInterval;
    public final double recalcDistance;
    public final long recalcCooldownMs;
    public final double arriveDistance;
    public final double startSearchRadius;
    public final double offroadPenalty;
    public final PointerMode pointerMode;
    public final PointerEntity pointerEntity;
    public final Material pointerItem;
    public final boolean pointerGlowing;
    public final double pointerHeight;
    public final boolean npcGlow;
    public final boolean displayLibEnabled;
    public final boolean displayLibTrail;
    public final double npcHighlightDistance;
    public final double debugRadius;
    public final int debugInterval;
    public final boolean debugLabels;
    public final int particleBudget;
    public final String guideModel;
    public final boolean closeOnSelect;
    /** AUTO — экран DisplayLib, если он установлен; INVENTORY — всегда меню-инвентарь. */
    public final String guiStyle;
    public final double screenDistance;
    public final double screenHeight;
    public final long clickDebounceMs;
    public final int autosaveSeconds;

    public Settings(ConfigurationSection c) {
        prefix = c.getString("messages.prefix", "<dark_green>[<green>GPS</green>]</dark_green> ");
        linkRadius = Math.max(1, c.getDouble("road.linkRadius", 5.0));
        lineOfSight = c.getBoolean("road.checkLineOfSight", true);
        maxHeightDifference = Math.max(0, c.getDouble("road.maxHeightDifference", 4.0));
        manualLinkRadius = Math.max(linkRadius, c.getDouble("road.manualLinkRadius", 16.0));
        npcLinkRadius = Math.max(1, c.getDouble("road.npcLinkRadius", 5.0));
        pickDistance = Math.max(2, c.getDouble("road.pickDistance", 8.0));
        deleteConfirmMs = Math.max(500, c.getLong("road.deleteConfirmMs", 3000));
        zoneLinkRadius = Math.max(1, c.getDouble("zone.linkRadius", 5.0));
        pointerSpacing = Math.max(1, c.getDouble("route.pointerSpacing", 3.0));
        pointerMaxDistance = Math.max(pointerSpacing, c.getDouble("route.pointerMaxDistance", 17.0));
        routeInterval = Math.max(1, c.getInt("route.updateIntervalTicks", 5));
        recalcDistance = Math.max(2, c.getDouble("route.recalculateDistance", 3.0));
        recalcCooldownMs = Math.max(0, c.getLong("route.recalculateCooldownMs", 1000));
        arriveDistance = Math.max(1, c.getDouble("route.arriveDistance", 3.0));
        startSearchRadius = Math.max(linkRadius, c.getDouble("route.startSearchRadius", 48.0));
        offroadPenalty = Math.max(1.0, c.getDouble("route.offroadPenalty", 3.0));
        PointerMode pm;
        try {
            pm = PointerMode.valueOf(c.getString("pointer.mode", "PACKET").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            pm = PointerMode.PACKET;
        }
        pointerMode = pm;
        PointerEntity pe;
        try {
            pe = PointerEntity.valueOf(c.getString("pointer.entity", "SHULKER_BULLET").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            pe = PointerEntity.SHULKER_BULLET;
        }
        pointerEntity = pe;
        Material m = Material.matchMaterial(c.getString("pointer.displayItem", "SHULKER_SHELL"));
        pointerItem = m != null && m.isItem() ? m : Material.SHULKER_SHELL;
        pointerGlowing = c.getBoolean("pointer.glowing", true);
        pointerHeight = c.getDouble("pointer.height", 0.5);
        npcGlow = c.getBoolean("pointer.npcGlow", true);
        displayLibEnabled = c.getBoolean("displayLib.enabled", true);
        displayLibTrail = c.getBoolean("displayLib.trail", true);
        npcHighlightDistance = c.getDouble("pointer.npcHighlightDistance", 64.0);
        debugRadius = Math.max(4, c.getDouble("debug.radius", 32.0));
        debugInterval = Math.max(1, c.getInt("debug.updateIntervalTicks", 4));
        debugLabels = c.getBoolean("debug.labels", true);
        particleBudget = Math.max(100, c.getInt("debug.particleBudget", 2500));
        guideModel = c.getString("guide.itemModel", "");
        closeOnSelect = c.getBoolean("gui.closeOnSelect", true);
        guiStyle = c.getString("gui.style", "AUTO").toUpperCase(Locale.ROOT);
        screenDistance = Math.max(0.5, c.getDouble("gui.screenDistance", 0.75));
        screenHeight = c.getDouble("gui.screenHeight", 0.1);
        clickDebounceMs = Math.max(0, c.getLong("gui.clickDebounceMs", 250));
        autosaveSeconds = Math.max(5, c.getInt("storage.autosaveSeconds", 60));
    }
}
