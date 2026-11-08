package com.gpsnavigation.gui;

import com.gpsnavigation.util.Msg;

/** Выбор карты местности (показывается, только если карт несколько). */
public final class MainMenu extends GpsMenu {

    public MainMenu(int rows, int page) {
        super(Msg.mm("<dark_gray>GPS — выберите карту"), rows, page);
    }
}
