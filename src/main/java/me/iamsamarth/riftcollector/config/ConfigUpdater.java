package me.iamsamarth.riftcollector.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings a config file up to date with the one shipped in the jar.
 *
 * <p>Keys that are new are added with their comments, and every value the owner set is left as it
 * is. Keys the plugin used to ship and no longer does are removed, while keys the owner added
 * themselves are kept: the difference is told apart by remembering, in .managed, which keys came
 * from the jar last time. Menu files additionally have their character grid merged, so a button
 * added in an update actually appears instead of silently never being drawn.
 */
public final class ConfigUpdater {

    /** How much of a file the plugin considers its own. */
    private enum Mode {
        /** Settings: a missing key is put back with its default, a retired one is dropped. */
        SETTINGS,
        /** A settings file that also has a character grid to merge new buttons into. */
        MENU,
        /**
         * A list the owner curates, such as the prices. An entry the jar ships is written once
         * and never again, so deleting one keeps it deleted instead of coming back every reload.
         */
        DATA
    }

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final String MANIFEST_FOLDER = ".managed";
    private static final int COLUMNS = 9;

    private ConfigUpdater() {
    }

    public static void update(Plugin plugin, String... resources) {
        for (String resource : resources) {
            apply(plugin, resource, Mode.SETTINGS);
        }
    }

    /** Files whose entries are the owner's to add and remove, where only brand new ones appear. */
    public static void updateData(Plugin plugin, String... resources) {
        for (String resource : resources) {
            apply(plugin, resource, Mode.DATA);
        }
    }

    /** Menu files, where the layout grid is merged as well as the keys. */
    public static void updateMenus(Plugin plugin, String... names) {
        for (String name : names) {
            apply(plugin, "gui/" + name + ".yml", Mode.MENU);
        }
    }

    private static void apply(Plugin plugin, String resource, Mode mode) {
        Logger logger = plugin.getLogger();
        try {
            File file = new File(plugin.getDataFolder(), resource);
            if (!file.exists()) {
                plugin.saveResource(resource, false);
                writeManifest(plugin, resource, keysOf(bundled(plugin, resource)));
                return;
            }

            YamlConfiguration bundled = bundled(plugin, resource);
            if (bundled == null) {
                return;
            }
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
            Set<String> shipped = keysOf(bundled);
            Set<String> managed = readManifest(plugin, resource);

            List<String> added = mode == Mode.DATA
                    ? addNewlyShipped(bundled, current, managed)
                    : addMissing(bundled, current);
            List<String> removed = mode == Mode.DATA
                    ? List.of()
                    : removeStale(current, shipped, managed);
            List<String> placed = mode == Mode.MENU
                    ? mergeLayout(bundled, current, resource, logger)
                    : List.of();

            // A data file remembers every key it has ever shipped, so a deleted entry stays
            // deleted even once the jar stops listing it.
            Set<String> record = shipped;
            if (mode == Mode.DATA) {
                record = new LinkedHashSet<>(managed);
                record.addAll(shipped);
            }

            if (added.isEmpty() && removed.isEmpty() && placed.isEmpty()) {
                writeManifest(plugin, resource, record);
                return;
            }

            current.options().setHeader(bundled.options().getHeader());
            backup(file);
            current.save(file);
            writeManifest(plugin, resource, record);

            if (!added.isEmpty()) {
                logger.info("Added to " + resource + ": " + String.join(", ", added));
            }
            if (!removed.isEmpty()) {
                logger.info("Removed settings no longer used from " + resource + ": "
                        + String.join(", ", removed));
            }
            if (!placed.isEmpty()) {
                logger.info("Placed new buttons in " + resource + ": " + String.join(", ", placed));
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not update " + resource, exception);
        }
    }

    static List<String> addMissing(YamlConfiguration bundled, YamlConfiguration current) {
        List<String> added = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                if (!current.isConfigurationSection(key)) {
                    current.createSection(key);
                    added.add(key);
                }
            } else if (!current.contains(key, true)) {
                current.set(key, bundled.get(key));
                added.add(key);
            }
            // Comments are documentation, not settings, so they follow the jar either way.
            current.setComments(key, bundled.getComments(key));
            current.setInlineComments(key, bundled.getInlineComments(key));
        }
        return added;
    }

    /**
     * Adds only entries the jar has started shipping since the last run. Anything it already
     * wrote once is the owner's from then on, so the entries they deleted are not written back.
     */
    static List<String> addNewlyShipped(YamlConfiguration bundled, YamlConfiguration current,
                                        Set<String> managed) {
        List<String> added = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (!managed.contains(key) && !current.contains(key, true)) {
                if (bundled.isConfigurationSection(key)) {
                    current.createSection(key);
                } else {
                    current.set(key, bundled.get(key));
                }
                added.add(key);
            }
            if (current.contains(key, true)) {
                current.setComments(key, bundled.getComments(key));
                current.setInlineComments(key, bundled.getInlineComments(key));
            }
        }
        return added;
    }

    /** Only keys this plugin wrote before and no longer ships; the owner's own keys are kept. */
    static List<String> removeStale(YamlConfiguration current, Set<String> shipped, Set<String> managed) {
        List<String> stale = new ArrayList<>();
        for (String key : managed) {
            if (!shipped.contains(key) && current.contains(key, true)) {
                stale.add(key);
            }
        }
        // Deepest first, so removing a parent cannot hide a child that is still listed.
        stale.sort(Comparator.comparingInt((String key) -> key.split("\\.").length).reversed());
        for (String key : stale) {
            current.set(key, null);
        }
        return stale;
    }

    /**
     * Copies characters the shipped grid has and the owner's does not into their grid, preferring
     * the same cell and otherwise the first decorative cell, searching from the bottom row where
     * buttons usually sit.
     */
    static List<String> mergeLayout(YamlConfiguration bundled, YamlConfiguration current,
                                            String resource, Logger logger) {
        List<String> bundledGrid = bundled.getStringList("layout");
        List<String> grid = new ArrayList<>(current.getStringList("layout"));
        if (bundledGrid.isEmpty() || grid.isEmpty()) {
            return List.of();
        }

        char contentCharacter = first(current.getString("content-character", "#"), '#');
        Set<Character> present = new HashSet<>();
        for (String row : grid) {
            for (char character : row.toCharArray()) {
                present.add(character);
            }
        }

        List<String> placed = new ArrayList<>();
        for (int row = 0; row < bundledGrid.size(); row++) {
            String line = bundledGrid.get(row);
            for (int column = 0; column < Math.min(COLUMNS, line.length()); column++) {
                char character = line.charAt(column);
                if (character == ' ' || character == contentCharacter || present.contains(character)) {
                    continue;
                }
                int slot = place(grid, current, contentCharacter, row, column, character);
                if (slot < 0) {
                    logger.warning(resource + " has no free decorative slot for the new '" + character
                            + "' button; add it to the layout by hand to use it.");
                    continue;
                }
                present.add(character);
                placed.add(character + " at slot " + slot);
            }
        }

        if (!placed.isEmpty()) {
            current.set("layout", grid);
        }
        return placed;
    }

    private static int place(List<String> grid, YamlConfiguration current, char contentCharacter,
                             int preferredRow, int preferredColumn, char character) {
        if (replaceable(grid, current, contentCharacter, preferredRow, preferredColumn)) {
            return write(grid, preferredRow, preferredColumn, character);
        }
        for (int row = grid.size() - 1; row >= 0; row--) {
            for (int column = 0; column < COLUMNS; column++) {
                if (replaceable(grid, current, contentCharacter, row, column)) {
                    return write(grid, row, column, character);
                }
            }
        }
        return -1;
    }

    /** A cell holding nothing, or a filler whose item hides its tooltip, may be written over. */
    private static boolean replaceable(List<String> grid, YamlConfiguration current, char contentCharacter,
                                       int row, int column) {
        if (row < 0 || row >= grid.size() || column < 0 || column >= COLUMNS) {
            return false;
        }
        String line = grid.get(row);
        char character = column < line.length() ? line.charAt(column) : ' ';
        if (character == ' ') {
            return true;
        }
        if (character == contentCharacter) {
            return false;
        }
        ConfigurationSection item = current.getConfigurationSection("items." + character);
        return item != null && item.getBoolean("hide-tooltip", false);
    }

    private static int write(List<String> grid, int row, int column, char character) {
        StringBuilder line = new StringBuilder(grid.get(row));
        while (line.length() < COLUMNS) {
            line.append(' ');
        }
        line.setCharAt(column, character);
        grid.set(row, line.toString());
        return row * COLUMNS + column;
    }

    private static Set<String> keysOf(YamlConfiguration config) {
        return config == null ? Set.of() : new LinkedHashSet<>(config.getKeys(true));
    }

    private static Set<String> readManifest(Plugin plugin, String resource) {
        File file = manifest(plugin, resource);
        if (!file.exists()) {
            // Nothing was recorded yet, so nothing is known to be stale; only additions happen.
            return Set.of();
        }
        return new LinkedHashSet<>(YamlConfiguration.loadConfiguration(file).getStringList("keys"));
    }

    private static void writeManifest(Plugin plugin, String resource, Set<String> keys) throws IOException {
        File file = manifest(plugin, resource);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        YamlConfiguration manifest = new YamlConfiguration();
        manifest.options().setHeader(List.of(
                "Keys RiftCollector itself wrote into " + resource + ".",
                "It uses this to tell its own outdated settings from yours. Do not edit."));
        manifest.set("keys", new ArrayList<>(keys));
        manifest.save(file);
    }

    private static File manifest(Plugin plugin, String resource) {
        String name = resource.replace('/', '_').replaceAll("\\.yml$", "");
        return new File(new File(plugin.getDataFolder(), MANIFEST_FOLDER), name + ".keys.yml");
    }

    private static void backup(File file) throws IOException {
        File backup = new File(file.getParentFile(),
                file.getName() + ".backup-" + LocalDateTime.now().format(BACKUP_STAMP));
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static YamlConfiguration bundled(Plugin plugin, String resource) throws IOException {
        InputStream stream = plugin.getResource(resource);
        if (stream == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static char first(String raw, char fallback) {
        return raw == null || raw.isEmpty() ? fallback : raw.charAt(0);
    }
}
