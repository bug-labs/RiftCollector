package me.iamsamarth.riftcollector.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.collector.CollectorService;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * What one player's collectors add up to, as %riftcollector_...% placeholders. Only reachable
 * when PlaceholderAPI is installed, because nothing else refers to this class.
 *
 * <p>Every one of these walks the owner's collectors, which is cheap for the handful a player
 * has but is still called as often as whatever shows it redraws, so nothing here asks a shop
 * plugin for a price.
 */
final class CollectorPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final CollectorService collectors;
    private final Upgrades upgrades;
    private final EconomyService economy;

    CollectorPlaceholders(Plugin plugin, CollectorService collectors, Upgrades upgrades,
                          EconomyService economy) {
        this.plugin = plugin;
        this.collectors = collectors;
        this.upgrades = upgrades;
        this.economy = economy;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "riftcollector";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Stays registered across a PlaceholderAPI reload, since this plugin owns it. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of("%riftcollector_collectors%", "%riftcollector_items%",
                "%riftcollector_slots_used%", "%riftcollector_slots%",
                "%riftcollector_earnings%", "%riftcollector_earnings_raw%",
                "%riftcollector_sold%");
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String parameter) {
        if (player == null) {
            return null;
        }
        Totals totals = totalsFor(player);
        return switch (parameter.toLowerCase(Locale.ROOT)) {
            case "collectors" -> Long.toString(totals.count);
            case "items" -> Long.toString(totals.items);
            case "slots_used" -> Long.toString(totals.slotsUsed);
            case "slots" -> Long.toString(totals.slots);
            case "earnings" -> economy.format(totals.earnings);
            case "earnings_raw" -> Double.toString(totals.earnings);
            case "sold" -> Long.toString(totals.sold);
            default -> null;
        };
    }

    private Totals totalsFor(OfflinePlayer player) {
        Totals totals = new Totals();
        for (Collector collector : collectors.index().all()) {
            if (!player.getUniqueId().equals(collector.owner())) {
                continue;
            }
            totals.count++;
            totals.items += collector.itemCount();
            totals.slotsUsed += collector.usedSlots();
            totals.slots += upgrades.slots(collector);
            totals.earnings += collector.earnings();
            totals.sold += collector.itemsSold();
        }
        return totals;
    }

    private static final class Totals {
        private long count;
        private long items;
        private long slotsUsed;
        private long slots;
        private double earnings;
        private long sold;
    }
}
