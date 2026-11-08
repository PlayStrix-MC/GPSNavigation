package com.gpsnavigation.gui;

import com.gpsnavigation.util.Msg;

/** Список NPC карты. */
public final class NpcMenu extends GpsMenu {

    private final String mapId;

    public NpcMenu(String mapId, String mapName, int rows, int page) {
        super(Msg.mm("<dark_gray>GPS — " + Msg.esc(mapName)), rows, page);
        this.mapId = mapId;
    }

    public String mapId() {
        return mapId;
    }
}
