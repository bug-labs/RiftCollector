package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.ability.SellService;
import me.iamsamarth.riftcollector.collector.CollectorItem;
import me.iamsamarth.riftcollector.collector.CollectorService;
import me.iamsamarth.riftcollector.config.PluginConfig;
import me.iamsamarth.riftcollector.hologram.HologramManager;
import me.iamsamarth.riftcollector.hook.EconomyService;
import me.iamsamarth.riftcollector.upgrade.UpgradeService;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import org.bukkit.plugin.Plugin;

/** Everything the menus need from the rest of the plugin, handed over in one piece. */
public record MenuServices(Plugin plugin,
                           Upgrades upgrades,
                           UpgradeService purchases,
                           EconomyService economy,
                           SellService sales,
                           HologramManager holograms,
                           ChatPrompt prompts,
                           CollectorService collectors,
                           CollectorItem collectorItem,
                           PluginConfig.Names names) {
}
