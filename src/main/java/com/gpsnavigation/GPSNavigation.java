package com.gpsnavigation;

import com.gpsnavigation.command.GpsCommand;
import com.gpsnavigation.command.MapCommand;
import com.gpsnavigation.command.NpcCommand;
import com.gpsnavigation.command.RoadCommand;
import com.gpsnavigation.command.ZoneCommand;
import com.gpsnavigation.core.RoadService;
import com.gpsnavigation.data.DataWriter;
import com.gpsnavigation.data.MapManager;
import com.gpsnavigation.data.NpcManager;
import com.gpsnavigation.data.RoadNetwork;
import com.gpsnavigation.data.DataStore;
import com.gpsnavigation.data.ConfigFile;
import com.gpsnavigation.data.ZoneManager;
import com.gpsnavigation.debug.DebugRenderer;
import com.gpsnavigation.editor.EditorSessions;
import com.gpsnavigation.editor.RoadEditor;
import com.gpsnavigation.editor.ZoneEditor;
import com.gpsnavigation.gui.GpsMenu;
import com.gpsnavigation.gui.GuiManager;
import com.gpsnavigation.item.SpecialItems;
import com.gpsnavigation.listener.GuiListener;
import com.gpsnavigation.listener.ItemListener;
import com.gpsnavigation.listener.PlayerListener;
import com.gpsnavigation.route.RouteManager;
import com.gpsnavigation.route.VisualFactory;
import com.gpsnavigation.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public final class GPSNavigation extends JavaPlugin {

    private Settings settings;
    private DataWriter writer;
    private MapManager maps;
    private RoadNetwork network;
    private ZoneManager zones;
    private NpcManager npcs;
    private RoadService roads;
    private RouteManager routes;
    private VisualFactory visuals;
    private com.gpsnavigation.route.PacketBridge packets;
    private com.gpsnavigation.route.DisplayLibVisuals displayLib;
    private com.gpsnavigation.gui.DisplayLibMenu displayLibMenu;
    private DebugRenderer debug;
    private SpecialItems items;
    private EditorSessions editors;
    private RoadEditor roadEditor;
    private ZoneEditor zoneEditor;
    private GuiManager gui;

    private BukkitTask routeTask;
    private BukkitTask debugTask;
    private BukkitTask autosaveTask;
    private boolean saveScheduled;

    @Override
    public void onEnable() {
        settings = new Settings(loadConfig());
        Msg.setPrefix(settings.prefix);

        writer = new DataWriter(getLogger());
        maps = new MapManager(getDataFolder());
        network = new RoadNetwork(getDataFolder(), getLogger());
        zones = new ZoneManager(getDataFolder(), getLogger());
        npcs = new NpcManager(getDataFolder());
        loadData();

        packets = new com.gpsnavigation.route.PacketBridge(getLogger());
        visuals = new VisualFactory(this);
        roads = new RoadService(this);
        routes = new RouteManager(this);
        debug = new DebugRenderer(this);
        items = new SpecialItems(this);
        editors = new EditorSessions(this);
        roadEditor = new RoadEditor(this);
        zoneEditor = new ZoneEditor(this);
        gui = new GuiManager(this);

        hookDisplayLib();
        if (displayLib != null) getLogger().info("Указатели GPS: DisplayLib (тропа Ока Края, пакетами).");
        else if (packets.isAvailable()) getLogger().info("Указатели GPS: пакеты (без серверных сущностей).");
        int stale = visuals.cleanupStale();
        if (stale > 0) getLogger().info("Удалено оставшихся визуальных сущностей: " + stale);

        getServer().getPluginManager().registerEvents(new ItemListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new com.gpsnavigation.listener.CommandVisibilityListener(), this);

        register("gps", new GpsCommand(this));
        register("road", new RoadCommand(this));
        register("gpszone", new ZoneCommand(this));
        register("gpsnpc", new NpcCommand(this));
        register("gpsmap", new MapCommand(this));

        startTasks();
        getLogger().info("GPSNavigation включён: карт " + maps.all().size() + ", маркеров " + network.all().size()
                + ", точек зон " + zones.points().size() + ", NPC " + npcs.all().size());
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (routes != null) routes.stopAll();
        if (displayLib != null) {
            try {
                displayLib.shutdown();
            } catch (Throwable ignored) {
            }
        }
        if (debug != null) debug.clearAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof GpsMenu) p.closeInventory();
        }
        if (writer != null) {
            saveNow(false);
            writer.shutdown();
        }
    }

    /** Подключение DisplayLib, если он установлен (softdepend) и не выключен в конфиге. */
    private void hookDisplayLib() {
        displayLib = null;
        if (!settings.displayLibEnabled) return;
        org.bukkit.plugin.Plugin dl = getServer().getPluginManager().getPlugin("DisplayLib");
        if (dl == null || !dl.isEnabled()) return;
        muteScreenOpenLog(dl);
        try {
            displayLib = new com.gpsnavigation.route.DisplayLibVisuals(this);
        } catch (Throwable t) {
            displayLib = null;
            getLogger().warning("Не удалось подключить DisplayLib (" + t + ") — используются пакетные указатели.");
            return;
        }
        try {
            if (displayLibMenu == null) {
                displayLibMenu = new com.gpsnavigation.gui.DisplayLibMenu(this);
                getServer().getPluginManager().registerEvents(displayLibMenu, this);
            }
        } catch (Throwable t) {
            displayLibMenu = null;
            getLogger().warning("Меню на экранах DisplayLib недоступно (" + t + ") — используется меню-инвентарь.");
        }
    }

    /** DisplayLib пишет в консоль каждое открытие экрана — для меню навигатора это шум, глушим только его строки. */
    private static void muteScreenOpenLog(org.bukkit.plugin.Plugin dl) {
        try {
            java.util.logging.Logger log = dl.getLogger();
            if (log.getFilter() instanceof OpenLogFilter) return;
            log.setFilter(new OpenLogFilter(log.getFilter()));
        } catch (Throwable ignored) {
        }
    }

    private static final class OpenLogFilter implements java.util.logging.Filter {
        private final java.util.logging.Filter next;
        OpenLogFilter(java.util.logging.Filter next) { this.next = next; }
        @Override
        public boolean isLoggable(java.util.logging.LogRecord r) {
            String m = r.getMessage();
            if (m != null && m.contains("screen 'gps_navigator'")) return false;
            return next == null || next.isLoggable(r);
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Команда /" + name + " не объявлена в plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    private void loadData() {
        for (DataStore store : stores()) store.load(getLogger());
        // Маркеры с несуществующей картой не удаляются, но предупреждаем.
        long orphan = network.all().stream().filter(m -> maps.get(m.mapId()) == null).count();
        if (orphan > 0) getLogger().warning("Маркеров без существующей карты: " + orphan);
    }

    private List<DataStore> stores() {
        return List.of(maps, network, zones, npcs);
    }

    private void startTasks() {
        routeTask = Bukkit.getScheduler().runTaskTimer(this, () -> routes.tick(), 20L, settings.routeInterval);
        debugTask = Bukkit.getScheduler().runTaskTimer(this, () -> debug.tick(), 20L, settings.debugInterval);
        long autosave = settings.autosaveSeconds * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> saveNow(true), autosave, autosave);
    }

    private void stopTasks() {
        if (routeTask != null) routeTask.cancel();
        if (debugTask != null) debugTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();
    }

    /** Планирует сохранение изменённых данных на следующий тик (несколько изменений за тик — одна запись). */
    public void requestSave() {
        if (saveScheduled || !isEnabled()) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTask(this, () -> {
            saveScheduled = false;
            saveNow(true);
        });
    }

    /** Сохраняет изменённые хранилища. Снимок делается в основном потоке, запись — асинхронно. */
    public void saveNow(boolean async) {
        for (DataStore store : stores()) {
            if (!store.isDirty()) continue;
            String data = store.snapshot();
            store.clearDirty();
            if (async) writer.writeAsync(store.file(), data);
            else writer.writeSync(store.file(), data);
        }
    }

    /** /gps reload: сохранить, перечитать config.jmc и данные, пересчитать маршруты. */
    public void reloadAll() {
        saveNow(false);
        settings = new Settings(loadConfig());
        Msg.setPrefix(settings.prefix);
        routes.stopAll();
        debug.clearAll();
        if (displayLib == null) hookDisplayLib();
        loadData();
        stopTasks();
        startTasks();
    }

    public Settings settings() { return settings; }
    public MapManager maps() { return maps; }
    public RoadNetwork network() { return network; }
    public ZoneManager zones() { return zones; }
    public NpcManager npcs() { return npcs; }
    public RoadService roads() { return roads; }
    public RouteManager routes() { return routes; }
    public VisualFactory visuals() { return visuals; }
    public com.gpsnavigation.route.PacketBridge packets() { return packets; }
    public com.gpsnavigation.route.DisplayLibVisuals displayLib() { return displayLib; }

    /** Меню на экране DisplayLib или null (не установлен / gui.style: INVENTORY). */
    public com.gpsnavigation.gui.DisplayLibMenu displayLibMenu() {
        return "INVENTORY".equals(settings.guiStyle) ? null : displayLibMenu;
    }
    public DebugRenderer debug() { return debug; }
    public SpecialItems items() { return items; }
    public EditorSessions editors() { return editors; }
    public RoadEditor roadEditor() { return roadEditor; }
    public ZoneEditor zoneEditor() { return zoneEditor; }
    public GuiManager gui() { return gui; }

    private org.bukkit.configuration.ConfigurationSection loadConfig() {
        return ConfigFile.load(getDataFolder(), () -> getResource("config.jmc"), getLogger());
    }
}
