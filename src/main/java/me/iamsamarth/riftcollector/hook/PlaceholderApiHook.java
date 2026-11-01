package me.iamsamarth.riftcollector.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import me.iamsamarth.riftcollector.collector.CollectorService;
import me.iamsamarth.riftcollector.lang.Placeholders;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PlaceholderAPI, both ways round: its %placeholders% are filled into this plugin's menus,
 * messages and holograms, and this plugin's own %riftcollector_...% placeholders are offered to
 * every other plugin. Neither half exists when PlaceholderAPI is not installed, and no class of
 * it is ever touched in that case.
 */
public final class PlaceholderApiHook implements Placeholders {

    private final Plugin plugin;
    private final boolean present;

    public PlaceholderApiHook(Plugin plugin) {
        this.plugin = plugin;
        this.present = Hooks.pluginPresent(plugin, List.of("PlaceholderAPI"))
                && Hooks.classPresent("me.clip.placeholderapi.PlaceholderAPI");
    }

    public boolean available() {
        return present;
    }

    @Override
    public String apply(@Nullable OfflinePlayer target, String raw) {
        // The scan is not free, so a line without a single % never reaches PlaceholderAPI.
        if (!present || target == null || raw.indexOf('%') < 0) {
            return raw;
        }
        return PlaceholderAPI.setPlaceholders(target, raw);
    }

    /** Offers this plugin's own placeholders to everyone else. */
    public void register(CollectorService collectors, Upgrades upgrades, EconomyService economy) {
        if (!present) {
            return;
        }
        new CollectorPlaceholders(plugin, collectors, upgrades, economy).register();
        plugin.getLogger().info("Registered %riftcollector_...% placeholders with PlaceholderAPI.");
    }
}
