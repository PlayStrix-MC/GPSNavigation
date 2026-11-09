package com.gpsnavigation.gui;

import com.gpsnavigation.GPSNavigation;
import com.gpsnavigation.model.GpsNpc;
import com.gpsnavigation.model.TerrainMap;
import com.gpsnavigation.route.ActiveRoute;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import padej.displayLib.api.events.DisplayClickEvent;
import padej.displayLib.script.api.WidgetAPI;
import padej.displayLib.ui.ScreenInstance;
import padej.displayLib.ui.UIManager;
import padej.displayLib.ui.widgets.Widget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Меню путеводителя на экране DisplayLib (screens/gps_navigator.jmc).
 * <p>
 * Карточки NPC (по 6 на страницу): клик — выбор (зелёная полоса слева, галочка, имя подсвечивается),
 * повторный клик — снять выбор. Нижняя кнопка — «Проложить маршрут» / «Отменить маршрут».
 * Экран приватный и рисуется пакетами — его видит только этот игрок.
 * Класс загружается, только если установлен DisplayLib.
 */
public final class DisplayLibMenu implements Listener {

    public static final String SCREEN = "gps_navigator";
    private static final int PER_PAGE = 6;
    private static final double PX = 1.0 / 40.0;

    /** Общий масштаб меню — должен совпадать с масштабом вёрстки gps_navigator.jmc. */
    private static final double K = 0.4;
    /** Доп. множитель текста — должен совпадать с вёрсткой gps_navigator.jmc. */
    private static final double T = 1.3;
    // Масштабы из gps_navigator.jmc (нужны для выравнивания и анимаций)
    private static final double NAME_SCALE = 0.5 * K * T;
    private static final double DESC_SCALE = 0.36 * K * T;
    private static final double SUB_SCALE = 0.36 * K * T;
    private static final double CHECK_SCALE = 0.85 * K * T;
    private static final double ACCENT_SX = 0.05 * K * 40 / 49;
    private static final double ACCENT_SY = 4 * (0.62 - 0.14) * K;
    /** Ширина текстовой области карточки, блоки (от левого края текста до галочки). */
    private static final double TEXT_AREA = (2.12 - 0.56 - 0.42) * K;

    private static final class State {
        final List<String> npcIds = new ArrayList<>();
        int page;
        String selected;
    }

    private final GPSNavigation plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public DisplayLibMenu(GPSNavigation plugin) {
        this.plugin = plugin;
    }

    // ---------- Открытие ----------

    public boolean open(Player player) {
        State st = new State();
        for (GpsNpc npc : visibleNpcs(player)) st.npcIds.add(npc.id());
        ActiveRoute r = plugin.routes().get(player.getUniqueId());
        if (r != null && st.npcIds.contains(r.npcId())) {
            st.selected = r.npcId();
            st.page = st.npcIds.indexOf(r.npcId()) / PER_PAGE;
        }
        if (!UIManager.getInstance().openScreen(player, SCREEN, spawnLocation(player),
                player.getLocation().getYaw() + 180f, 0f)) return false;
        states.put(player.getUniqueId(), st);
        render(player, st, true);
        return true;
    }

    /**
     * Точка центра экрана: перед игроком по горизонтали (gui.screen-distance), на высоте глаз + gui.screen-height.
     * Экран всегда вертикальный и смотрит на игрока — не уходит в землю и не заваливается, если игрок смотрит вниз.
     */
    private org.bukkit.Location spawnLocation(Player player) {
        org.bukkit.Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        double dist = plugin.settings().screenDistance;
        org.bukkit.Location loc = new org.bukkit.Location(eye.getWorld(),
                eye.getX() + dx * dist, eye.getY() + plugin.settings().screenHeight, eye.getZ() + dz * dist);
        loc.setYaw(eye.getYaw() + 180f);
        loc.setPitch(0f);
        return loc;
    }

    /** NPC всех карт мира игрока (если в мире карт нет — все NPC), по карте и имени. */
    private List<GpsNpc> visibleNpcs(Player player) {
        List<GpsNpc> list = new ArrayList<>();
        for (TerrainMap map : plugin.maps().inWorld(player.getWorld().getName())) list.addAll(plugin.npcs().ofMap(map.id()));
        if (list.isEmpty()) list.addAll(plugin.npcs().all());
        list.sort(Comparator.comparing((GpsNpc n) -> n.mapId()).thenComparing(n -> n.displayName().toLowerCase(Locale.ROOT)));
        return list;
    }

    // ---------- Отрисовка ----------

    private ScreenInstance screen(Player player) {
        ScreenInstance si = UIManager.getInstance().getActiveScreen(player);
        return si != null && SCREEN.equals(si.getScreenId()) ? si : null;
    }

    private static WidgetAPI w(ScreenInstance si, String id) {
        Widget widget = si.getWidget(id);
        return widget == null ? null : new WidgetAPI(id, widget);
    }

    private void render(Player player, State st, boolean animate) {
        ScreenInstance si = screen(player);
        if (si == null) return;
        int pages = Math.max(1, (st.npcIds.size() + PER_PAGE - 1) / PER_PAGE);
        st.page = Math.max(0, Math.min(st.page, pages - 1));
        ActiveRoute route = plugin.routes().get(player.getUniqueId());
        String active = route == null ? null : route.npcId();

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = st.page * PER_PAGE + i;
            GpsNpc npc = idx < st.npcIds.size() ? plugin.npcs().get(st.npcIds.get(idx)) : null;
            renderCard(si, i, npc, npc != null && npc.id().equals(st.selected), npc != null && npc.id().equals(active), animate);
        }

        set(si, "empty_lbl", w -> w.visible(st.npcIds.isEmpty()));
        boolean paged = pages > 1;
        for (String id : new String[]{"btn_prev", "btn_prev_lbl", "btn_next", "btn_next_lbl", "page_lbl"}) {
            boolean show = paged && !(id.startsWith("btn_prev") && st.page == 0)
                    && !(id.startsWith("btn_next") && st.page >= pages - 1);
            set(si, id, w -> w.visible(show));
        }
        set(si, "page_lbl", w -> w.text(seg("Страница " + (st.page + 1) + " из " + pages, "#8b93a7")));

        String sub = st.selected != null && plugin.npcs().get(st.selected) != null
                ? "Цель: " + plugin.npcs().get(st.selected).displayName()
                : active != null && plugin.npcs().get(active) != null
                ? "Сейчас: " + plugin.npcs().get(active).displayName()
                : "Выберите, куда идти";
        setLeft(si, "hdr_sub", fit(sub, 3.6 * K, SUB_SCALE), "#8b93a7", SUB_SCALE);

        // Главная кнопка
        GpsNpc sel = st.selected == null ? null : plugin.npcs().get(st.selected);
        String text;
        String color;
        int[] bar;
        if (sel == null) {
            text = "Выберите NPC";
            color = "#8b93a7";
            bar = new int[]{90, 98, 120};
        } else if (sel.id().equals(active)) {
            text = "Отменить маршрут  ✕";
            color = "#ff7b7b";
            bar = new int[]{220, 70, 70};
        } else {
            text = "Проложить маршрут  ➜";
            color = "#7dffb0";
            bar = new int[]{61, 255, 138};
        }
        set(si, "btn_go_lbl", w -> w.text(seg(text, color)));
        set(si, "btn_go_bar", w -> w.bgColor(bar[0], bar[1], bar[2]));
    }

    private void renderCard(ScreenInstance si, int i, GpsNpc npc, boolean selected, boolean active, boolean animate) {
        String p = "card_" + i;
        boolean show = npc != null;
        for (String suffix : new String[]{"", "_icon", "_name", "_desc"}) {
            set(si, p + suffix, w -> w.visible(show));
        }
        if (!show) {
            set(si, p + "_accent", w -> w.visible(false));
            set(si, p + "_check", w -> w.visible(false));
            return;
        }
        String[] sprite = sprite(npc.icon());
        set(si, p + "_icon", w -> w.sprite(sprite[0], sprite[1]));
        setLeft(si, p + "_name", fit(npc.displayName(), TEXT_AREA, NAME_SCALE), selected ? "#7dffb0" : "#ffffff", NAME_SCALE);

        String desc;
        String descColor;
        if (active) {
            desc = "● Маршрут активен";
            descColor = "#55e6ff";
        } else if (plugin.network().get(npc.linkedMarker()) == null) {
            desc = "Нет дороги к NPC";
            descColor = "#ff7b7b";
        } else if (!npc.description().isEmpty()) {
            desc = npc.description().get(0);
            descColor = "#8b93a7";
        } else {
            TerrainMap map = plugin.maps().get(npc.mapId());
            desc = map == null ? npc.mapId() : map.displayName();
            descColor = "#8b93a7";
        }
        setLeft(si, p + "_desc", fit(desc, TEXT_AREA, DESC_SCALE), descColor, DESC_SCALE);

        WidgetAPI accent = w(si, p + "_accent");
        WidgetAPI check = w(si, p + "_check");
        if (accent == null || check == null) return;
        boolean wasShown = check.visible();
        accent.visible(selected);
        check.visible(selected);
        if (selected && animate && !wasShown) {
            // Галочка «выпрыгивает», полоса вырастает — клиент интерполирует за 4 тика.
            check.scale(0.01, 0.01, 1);
            accent.scale(ACCENT_SX, 0.01, 1);
            Bukkit.getScheduler().runTask(plugin, () -> {
                check.scale(CHECK_SCALE, CHECK_SCALE, 1, 4);
                accent.scale(ACCENT_SX, ACCENT_SY, 1, 4);
            });
        } else if (selected) {
            check.scale(CHECK_SCALE, CHECK_SCALE, 1);
            accent.scale(ACCENT_SX, ACCENT_SY, 1);
        }
    }

    /** Текст, выровненный по левому краю: позиция виджета в .jmc — левая граница, сдвиг считается по ширине текста. */
    private void setLeft(ScreenInstance si, String id, String text, String color, double scale) {
        WidgetAPI w = w(si, id);
        if (w == null) return;
        int k = textWidth(text);
        w.text(seg(text, color));
        // Клиент рисует строку от (1 - k/2) до (1 + k/2) px относительно якоря.
        w.translation((k / 2.0 - 1) * PX * scale, 0, 0);
    }

    private interface Op {
        void apply(WidgetAPI w);
    }

    private static void set(ScreenInstance si, String id, Op op) {
        WidgetAPI w = w(si, id);
        if (w != null) op.apply(w);
    }

    private static List<Map<String, Object>> seg(String text, String color) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("color", color);
        return List.of(m);
    }

    /** Спрайт для иконки NPC: предметы — item/..., блоки — block/..., голова игрока — бирка. */
    private static String[] sprite(Material m) {
        if (m == null || m == Material.PLAYER_HEAD || m.isAir()) return new String[]{"minecraft:items", "item/name_tag"};
        String key = m.name().toLowerCase(Locale.ROOT);
        return m.isBlock() ? new String[]{"minecraft:blocks", "block/" + key} : new String[]{"minecraft:items", "item/" + key};
    }

    // ---------- Ширина текста (как у шрифта клиента и DisplayLib) ----------

    static int charWidth(int cp) {
        switch (cp) {
            case 'i': case '!': case '.': case ',': case ':': case ';': case '|': case '\'': case '`': return 2;
            case 'l': return 3;
            case 't': case 'I': case '(': case ')': case '[': case ']': case '{': case '}': case '"': case '*': case ' ': return 4;
            case 'f': case 'k': case '<': case '>': return 5;
            default: return cp > 0x2E80 ? 9 : 6;
        }
    }

    static int textWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += charWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** Обрезает текст многоточием, чтобы он поместился в maxBlocks при данном масштабе. */
    static String fit(String s, double maxBlocks, double scale) {
        int maxPx = (int) Math.floor(maxBlocks / (PX * scale));
        if (textWidth(s) <= maxPx) return s;
        int ell = charWidth('…');
        StringBuilder b = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = charWidth(cp);
            if (w + cw + ell > maxPx) break;
            b.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return b.toString().trim() + "…";
    }

    // ---------- Клики ----------

    @EventHandler
    public void onClick(DisplayClickEvent e) {
        if (!SCREEN.equals(e.getScreenId()) || e.getWidgetId() == null) return;
        Player player = e.getPlayer();
        State st = states.get(player.getUniqueId());
        if (st == null) return;
        String id = e.getWidgetId();
        if (id.startsWith("card_") && id.indexOf('_', 5) < 0) {
            int idx = st.page * PER_PAGE + Integer.parseInt(id.substring(5));
            if (idx >= st.npcIds.size()) return;
            String npcId = st.npcIds.get(idx);
            st.selected = npcId.equals(st.selected) ? null : npcId;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, st.selected == null ? 0.8f : 1.6f);
            render(player, st, true);
            return;
        }
        switch (id) {
            case "btn_prev":
                if (st.page > 0) {
                    st.page--;
                    render(player, st, true);
                }
                break;
            case "btn_next":
                if ((st.page + 1) * PER_PAGE < st.npcIds.size()) {
                    st.page++;
                    render(player, st, true);
                }
                break;
            case "btn_go":
                go(player, st);
                break;
            default:
                break;
        }
    }

    private void go(Player player, State st) {
        GpsNpc npc = st.selected == null ? null : plugin.npcs().get(st.selected);
        if (npc == null) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (plugin.routes().isRouteTo(player, npc.id())) {
            plugin.routes().stop(player.getUniqueId(), true);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
            render(player, st, false);
            return;
        }
        if (plugin.routes().start(player, npc)) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
            states.remove(player.getUniqueId());
            // Закрываем на следующем тике — не посреди обработки клика DisplayLib.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (screen(player) != null) UIManager.getInstance().closeScreen(player);
            });
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            render(player, st, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }
}
