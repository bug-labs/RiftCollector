package me.iamsamarth.riftcollector.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public record PluginConfig(String language,
                           Item item,
                           Database database,
                           Storage storage,
                           Limits limits,
                           Names names,
                           Collection collection,
                           Economy economy,
                           Holograms holograms,
                           Updates updates) {

    private static final Material DEFAULT_MATERIAL = Material.HOPPER;

    /** Only the block a collector is placed as; its name and lore come from the language file. */
    public record Item(Material material) {
    }

    public enum DatabaseType {
        SQLITE, MYSQL
    }

    public enum RangeType {
        /** The pickup range upgrade counts chunks around the collector's own chunk. */
        CHUNK,
        /** The pickup range upgrade counts blocks. */
        RADIUS
    }

    public record Database(DatabaseType type,
                           String host,
                           int port,
                           String database,
                           String username,
                           String password,
                           int poolSize,
                           boolean useSsl) {
    }

    public record Storage(long saveIntervalTicks, boolean importLegacyChunkData) {
    }

    public record Limits(int collectorsPerChunk) {
    }

    /**
     * Collector names typed by players. Formatting is off by default because a MiniMessage tag
     * can do more than colour text, and a name is shown to everyone who opens the collector.
     */
    public record Names(int maxLength, boolean allowFormatting) {
    }

    public record Collection(RangeType rangeType,
                             Set<Material> ignoredMaterials,
                             List<String> priceProviders,
                             String sellCurrency) {
    }

    /** Which economy plugins to use, and the currency a price falls back to. */
    public record Economy(List<String> providers, String defaultCurrency) {
    }

    public record Holograms(boolean enabled,
                            double offsetY,
                            long updateIntervalTicks,
                            List<String> lines,
                            HologramStyle style) {
    }

    /** How the text display entity behind a hologram looks. */
    public record HologramStyle(boolean textShadow,
                                boolean seeThrough,
                                String billboard,
                                String alignment,
                                double scale,
                                int lineWidth,
                                int textOpacity,
                                boolean background,
                                String backgroundColor,
                                int backgroundOpacity,
                                int brightness,
                                double viewRange) {
    }

    public record Updates(boolean enabled,
                          String project,
                          long checkIntervalTicks,
                          boolean notifyOnJoin,
                          String notifyPermission) {
    }

    public static PluginConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        return new PluginConfig(
                config.getString("language", "en_US"),
                new Item(readMaterial(config.getString("item.material"), logger)),
                new Database(
                        readEnum(config.getString("storage.type"), DatabaseType.class, DatabaseType.SQLITE,
                                "storage.type", logger),
                        config.getString("storage.mysql.host", "localhost"),
                        config.getInt("storage.mysql.port", 3306),
                        config.getString("storage.mysql.database", "riftcollector"),
                        config.getString("storage.mysql.username", "root"),
                        config.getString("storage.mysql.password", ""),
                        Math.clamp(config.getInt("storage.mysql.pool-size", 6), 1, 50),
                        config.getBoolean("storage.mysql.use-ssl", false)),
                new Storage(
                        Math.max(1L, config.getLong("storage.save-interval-seconds", 300L)) * 20L,
                        config.getBoolean("storage.import-legacy-chunk-data", true)),
                new Limits(Math.max(1, config.getInt("limits.collectors-per-chunk", 1))),
                new Names(
                        // 64 is what the name column of collector_settings holds.
                        Math.clamp(config.getInt("names.max-length", 32), 1, 64),
                        config.getBoolean("names.allow-formatting", false)),
                new Collection(
                        readEnum(config.getString("collection.range-type"), RangeType.class, RangeType.CHUNK,
                                "collection.range-type", logger),
                        readIgnoredMaterials(config.getStringList("collection.ignored-materials"), logger),
                        readPriceProviders(config.getStringList("collection.price-providers")),
                        config.getString("collection.sell-currency", "")),
                new Economy(
                        readEconomyProviders(config.getStringList("economy.providers")),
                        config.getString("economy.default-currency", "")),
                new Holograms(
                        config.getBoolean("holograms.enabled", true),
                        config.getDouble("holograms.offset-y", 1.4),
                        Math.max(1L, config.getLong("holograms.update-interval-seconds", 5L)) * 20L,
                        List.copyOf(config.getStringList("holograms.lines")),
                        new HologramStyle(
                                config.getBoolean("holograms.style.text-shadow", true),
                                config.getBoolean("holograms.style.see-through", false),
                                config.getString("holograms.style.billboard", "CENTER"),
                                config.getString("holograms.style.alignment", "CENTER"),
                                Math.max(0.1, config.getDouble("holograms.style.scale", 1.0)),
                                Math.max(1, config.getInt("holograms.style.line-width", 200)),
                                config.getInt("holograms.style.text-opacity", -1),
                                config.getBoolean("holograms.style.background.enabled", false),
                                config.getString("holograms.style.background.color", "#000000"),
                                Math.clamp(config.getInt("holograms.style.background.opacity", 64), 0, 255),
                                config.getInt("holograms.style.brightness", -1),
                                Math.max(0.1, config.getDouble("holograms.style.view-range", 1.0)))),
                new Updates(
                        config.getBoolean("updates.enabled", true),
                        config.getString("updates.project", "").trim(),
                        Math.max(1L, config.getLong("updates.check-interval-hours", 6L)) * 72_000L,
                        config.getBoolean("updates.notify-on-join", true),
                        config.getString("updates.notify-permission", "riftcollector.update-notify"))
        );
    }

    private static List<String> readEconomyProviders(List<String> configured) {
        return configured.isEmpty()
                ? List.of("EXCELLENT_ECONOMY", "VAULT")
                : List.copyOf(configured);
    }

    private static List<String> readPriceProviders(List<String> configured) {
        return configured.isEmpty()
                ? List.of("CUSTOM", "SHOPGUI_PLUS", "ECONOMY_SHOP_GUI")
                : List.copyOf(configured);
    }

    private static <T extends Enum<T>> T readEnum(String name, Class<T> type, T fallback, String path, Logger logger) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warning(path + " '" + name + "' is not valid, using " + fallback + ".");
            return fallback;
        }
    }

    private static Material readMaterial(String name, Logger logger) {
        if (name == null || name.isBlank()) {
            return DEFAULT_MATERIAL;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            logger.warning("item.material '" + name + "' is not a placeable block, falling back to "
                    + DEFAULT_MATERIAL + ".");
            return DEFAULT_MATERIAL;
        }
        return material;
    }

    private static Set<Material> readIgnoredMaterials(List<String> names, Logger logger) {
        Set<Material> ignored = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                logger.warning("Unknown material '" + name + "' in collection.ignored-materials, skipping it.");
                continue;
            }
            ignored.add(material);
        }
        return ignored;
    }
}
