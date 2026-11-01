package me.iamsamarth.riftcollector.hook;

import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.api.objects.SellPrice;
import me.gypopo.economyshopgui.util.EcoType;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sell prices from EconomyShopGUI. The same API covers EconomyShopGUI and EconomyShopGUI+, so
 * this one hook serves both editions; it returns null for items that are not in the shop.
 */
public final class EconomyShopGuiProvider implements PriceProvider {

    /** Both editions register the same API; the premium one has shipped under several names. */
    private static final List<String> PLUGIN_NAMES =
            List.of("EconomyShopGUI", "EconomyShopGUI+", "EconomyShopGUI-Premium", "EconomyShopGUIPremium");

    private final boolean present;

    public EconomyShopGuiProvider(Plugin plugin) {
        this.present = Hooks.pluginPresent(plugin, PLUGIN_NAMES)
                && Hooks.classPresent("me.gypopo.economyshopgui.api.EconomyShopGUIHook");
    }

    @Override
    public String id() {
        return "ECONOMY_SHOP_GUI";
    }

    @Override
    public boolean available() {
        return present;
    }

    @Override
    public double sellPrice(OfflinePlayer owner, ItemStack stack) {
        Optional<SellPrice> price = EconomyShopGUIHook.getSellPrice(owner, stack);
        if (price.isEmpty()) {
            return -1;
        }
        Map<EcoType, Double> prices = price.get().getPrices();
        if (prices.isEmpty()) {
            return -1;
        }
        // A shop item can be priced in several currencies. Payment here always goes through
        // Vault, so the Vault price is used when there is one.
        for (Map.Entry<EcoType, Double> entry : prices.entrySet()) {
            if ("VAULT".equalsIgnoreCase(entry.getKey().getType().name())) {
                return entry.getValue();
            }
        }
        return prices.values().iterator().next();
    }
}
