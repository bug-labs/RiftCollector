package me.iamsamarth.riftcollector;

import me.iamsamarth.riftcollector.ability.AutoSmelter;
import me.iamsamarth.riftcollector.ability.ItemPipeline;
import me.iamsamarth.riftcollector.ability.SellService;
import me.iamsamarth.riftcollector.collector.CollectionTask;
import me.iamsamarth.riftcollector.collector.CollectorItem;
import me.iamsamarth.riftcollector.collector.CollectorService;
import me.iamsamarth.riftcollector.command.CollectorCommand;
import me.iamsamarth.riftcollector.config.ConfigUpdater;
import me.iamsamarth.riftcollector.config.PluginConfig;
import me.iamsamarth.riftcollector.gui.ChatPrompt;
import me.iamsamarth.riftcollector.gui.MenuListener;
import me.iamsamarth.riftcollector.gui.MenuServices;
import me.iamsamarth.riftcollector.gui.Menus;
import me.iamsamarth.riftcollector.hologram.HologramManager;
import me.iamsamarth.riftcollector.hook.Hooks;
import me.iamsamarth.riftcollector.hook.ItemStackerHook;
import me.iamsamarth.riftcollector.hook.EconomyService;
import me.iamsamarth.riftcollector.hook.PlaceholderApiHook;
import me.iamsamarth.riftcollector.lang.Lang;
import me.iamsamarth.riftcollector.listener.CollectorListener;
import me.iamsamarth.riftcollector.storage.CollectorRepository;
import me.iamsamarth.riftcollector.storage.Database;
import me.iamsamarth.riftcollector.storage.LegacyChunkImporter;
import me.iamsamarth.riftcollector.update.UpdateChecker;
import me.iamsamarth.riftcollector.upgrade.UpgradeService;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.logging.Level;

public final class RiftCollector extends JavaPlugin {

    private PluginConfig config;
    private Lang lang;
    private Menus menus;
    private Upgrades upgrades;
    private EconomyService economy;
    private SellService sales;
    private ItemStackerHook stackers;
    private PlaceholderApiHook papi;
    private Database database;
    private CollectorService collectors;
    private CollectorItem collectorItem;
    private HologramManager holograms;
    private ChatPrompt prompts;
    private UpdateChecker updates;
    private BukkitTask collectionTask;
    private BukkitTask saveTask;
    private BukkitTask hologramTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Keeps an existing config, adding whatever this version introduced.
        ConfigUpdater.update(this, "config.yml", "lang/en_US.yml");
        // Prices are a list the owner curates, so a price they delete is not written back.
        ConfigUpdater.updateData(this, "prices.yml");
        ConfigUpdater.updateMenus(this, "collector", "upgrades", "settings", "filter", "trust");
        config = PluginConfig.load(this);
        lang = new Lang(this);
        papi = new PlaceholderApiHook(this);
        lang.placeholders(papi);
        lang.load(config.language());

        if (!connect()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyService(this);
        economy.hook(config.economy());
        stackers = new ItemStackerHook(this);
        stackers.hook();
        upgrades = Upgrades.load(getConfig(), getLogger());
        sales = new SellService(Hooks.priceProviders(this, config), economy, config.collection().sellCurrency());
        holograms = new HologramManager(collectors, lang, upgrades, economy,
                config.holograms(), config.names());
        collectors.onRemove(holograms::remove);
        collectorItem = new CollectorItem(this, lang);

        prompts = new ChatPrompt(this);
        getServer().getPluginManager().registerEvents(prompts, this);

        menus = new Menus(this, lang);
        menus.services(menuServices());
        menus.load();

        getServer().getPluginManager().registerEvents(
                new CollectorListener(this, collectors, collectorItem, lang), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        updates = new UpdateChecker(this, lang, config.updates());
        getServer().getPluginManager().registerEvents(updates, this);

        if (config.storage().importLegacyChunkData()) {
            LegacyChunkImporter importer = new LegacyChunkImporter(this, collectors);
            getServer().getPluginManager().registerEvents(importer, this);
            importer.importLoadedChunks();
        }

        PluginCommand command = getCommand("collector");
        if (command == null) {
            getLogger().severe("The /collector command is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CollectorCommand executor = new CollectorCommand(this, collectorItem, lang);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        papi.register(collectors, upgrades, economy);

        startTasks();
        updates.start(config.updates());
    }

    @Override
    public void onDisable() {
        if (updates != null) {
            updates.stop();
        }
        if (holograms != null) {
            holograms.removeAll();
        }
        if (menus != null) {
            menus.registry().closeAll();
        }
        if (collectors != null) {
            collectors.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public Lang lang() {
        return lang;
    }

    public Menus menus() {
        return menus;
    }

    public Upgrades upgrades() {
        return upgrades;
    }

    public HologramManager holograms() {
        return holograms;
    }

    public void reload() {
        menus.registry().closeAll();
        holograms.removeAll();
        collectors.flush();

        PluginConfig.Database previous = config.database();
        ConfigUpdater.update(this, "config.yml", "lang/en_US.yml");
        // Prices are a list the owner curates, so a price they delete is not written back.
        ConfigUpdater.updateData(this, "prices.yml");
        ConfigUpdater.updateMenus(this, "collector", "upgrades", "settings", "filter", "trust");
        reloadConfig();
        config = PluginConfig.load(this);
        if (!config.database().equals(previous)) {
            getLogger().warning("Database settings changed; restart the server to apply them.");
        }

        lang.load(config.language());
        economy.hook(config.economy());
        upgrades = Upgrades.load(getConfig(), getLogger());
        sales = new SellService(Hooks.priceProviders(this, config), economy, config.collection().sellCurrency());
        holograms.settings(config.holograms(), config.names(), upgrades);
        menus.services(menuServices());
        menus.load();

        startTasks();
        updates.start(config.updates());
    }

    private MenuServices menuServices() {
        return new MenuServices(this, upgrades, new UpgradeService(upgrades, economy), economy, sales,
                holograms, prompts, collectors, collectorItem, config.names());
    }

    private boolean connect() {
        try {
            database = new Database(config.database(), getDataFolder());
            collectors = new CollectorService(new CollectorRepository(database, getLogger()), getLogger());
            collectors.loadAll();
            return true;
        } catch (SQLException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not open the collector database", exception);
            if (database != null) {
                database.close();
                database = null;
            }
            return false;
        }
    }

    private void startTasks() {
        if (collectionTask != null) {
            collectionTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (hologramTask != null) {
            hologramTask.cancel();
        }

        ItemPipeline pipeline = new ItemPipeline(new AutoSmelter(), sales);
        // Every tick, so each collector can keep its own pickup speed.
        collectionTask = getServer().getScheduler().runTaskTimer(this,
                new CollectionTask(collectors, collectorItem, menus.registry(), upgrades, pipeline,
                        stackers, config, getLogger()), 1L, 1L);

        long saveInterval = config.storage().saveIntervalTicks();
        saveTask = getServer().getScheduler().runTaskTimer(this, collectors::flush, saveInterval, saveInterval);

        long hologramInterval = config.holograms().updateIntervalTicks();
        hologramTask = getServer().getScheduler().runTaskTimer(this, holograms::refreshAll,
                hologramInterval, hologramInterval);
    }
}
