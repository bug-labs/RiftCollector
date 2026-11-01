package me.iamsamarth.riftcollector.hook;

import net.brcdev.shopgui.ShopGuiPlusApi;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sell prices from ShopGUI+, which returns a negative price for items it does not sell. */
public final class ShopGuiPlusProvider implements PriceProvider {

    /** The plugin calls itself ShopGUIPlus; the others are what repacks and forks have used. */
    private static final List<String> PLUGIN_NAMES = List.of("ShopGUIPlus", "ShopGuiPlus", "ShopGUI+");

    private final Plugin plugin;
    private final boolean installed;
    private boolean failed;

    public ShopGuiPlusProvider(Plugin plugin) {
        this.plugin = plugin;
        this.installed = Hooks.pluginPresent(plugin, PLUGIN_NAMES)
                && Hooks.classPresent("net.brcdev.shopgui.ShopGuiPlusApi");
    }

    @Override
    public String id() {
        return "SHOPGUI_PLUS";
    }

    @Override
    public boolean installed() {
        return installed;
    }

    /**
     * ShopGUI+ reads its shops after every plugin has enabled, and asking it for a price before
     * that throws, so a collector sweeping in those first seconds has to wait for it.
     */
    @Override
    public boolean available() {
        if (!installed || failed) {
            return false;
        }
        try {
            return ShopGuiPlusApi.getPlugin() != null
                    && ShopGuiPlusApi.getPlugin().getShopManager() != null
                    && ShopGuiPlusApi.getPlugin().getShopManager().areShopsLoaded();
        } catch (RuntimeException | LinkageError error) {
            report("ShopGUI+ could not be asked whether its shops are loaded", error);
            return false;
        }
    }

    @Override
    public double sellPrice(OfflinePlayer owner, ItemStack stack) {
        Player online = owner.getPlayer();
        try {
            return online == null
                    ? ShopGuiPlusApi.getItemStackPriceSell(stack)
                    : ShopGuiPlusApi.getItemStackPriceSell(online, stack);
        } catch (RuntimeException | LinkageError error) {
            report("ShopGUI+ could not price " + stack.getType(), error);
            return -1;
        }
    }

    /** Logged once: a broken hook would otherwise fill the log, since sweeps run every tick. */
    private void report(String message, Throwable error) {
        if (failed) {
            return;
        }
        failed = true;
        Logger logger = plugin.getLogger();
        logger.log(Level.WARNING, message + "; its prices are switched off until the next reload.", error);
    }
}
