package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.upgrade.UpgradeService;
import me.iamsamarth.riftcollector.upgrade.UpgradeType;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.Nullable;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One icon per upgrade, each showing its level and what the next one costs. Storage is the odd
 * one out: it is bought by the slot, in bulk or a page at a time, so its icon answers to three
 * different clicks.
 */
public final class UpgradesMenu extends Menu {

    /** The item drawn for one upgrade in each of the states it can be in. */
    private record IconSet(String normal, String page, String maxed, String locked) {
    }

    private final Upgrades upgrades;
    private final Map<Integer, UpgradeType> slots = new HashMap<>();
    private final Map<UpgradeType, IconSet> icons = new EnumMap<>(UpgradeType.class);

    UpgradesMenu(Menus menus, MenuLayout layout, Collector collector) {
        super(menus, layout, collector);
        this.upgrades = menus.services().upgrades();
        readUpgradeSlots();
    }

    private void readUpgradeSlots() {
        ConfigurationSection section = layout.section("upgrades");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UpgradeType type = UpgradeType.byKey(key);
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (type == null || entry == null) {
                continue;
            }
            int slot = entry.getInt("slot", -1);
            if (slot < 0) {
                continue;
            }
            slots.put(slot, type);
            icons.put(type, new IconSet(
                    entry.getString("icon"),
                    entry.getString("page-icon", entry.getString("icon")),
                    entry.getString("maxed-icon", entry.getString("icon")),
                    entry.getString("locked-icon", entry.getString("icon"))));
        }
    }

    @Override
    protected void onMenuClick(InventoryClickEvent event, Player player) {
        UpgradeType type = slots.get(event.getSlot());
        if (type == null) {
            super.onMenuClick(event, player);
            return;
        }
        if (!canUse(player)) {
            lang.send(player, "upgrade.no-permission");
            return;
        }

        UpgradeService.Result result = type == UpgradeType.SLOTS
                ? services.purchases().purchaseSlots(player, collector, purchaseFor(event.getClick()))
                : services.purchases().purchase(player, collector, type);
        report(player, type, result);
    }

    /** Left buys one slot, right the bulk amount, shift-left a whole page. */
    private Upgrades.Purchase purchaseFor(ClickType click) {
        if (upgrades.slotModel().mode() == Upgrades.SlotMode.PAGE) {
            return Upgrades.Purchase.PAGE;
        }
        return switch (click) {
            case RIGHT, SHIFT_RIGHT -> Upgrades.Purchase.BULK;
            case SHIFT_LEFT -> Upgrades.Purchase.PAGE;
            default -> Upgrades.Purchase.ONE;
        };
    }

    private void report(Player player, UpgradeType type, UpgradeService.Result result) {
        switch (result) {
            case PURCHASED -> {
                lang.send(player, "upgrade.purchased",
                        Placeholder.component("upgrade", lang.get("upgrade." + type.key() + ".name")),
                        Placeholder.unparsed("level", Integer.toString(levelOf(type))));
                registry.refresh(collector);
                services.holograms().refresh(collector);
            }
            case MAX_LEVEL -> lang.send(player, "upgrade.max-level");
            case NO_PERMISSION -> lang.send(player, "upgrade.no-permission");
            case NO_ECONOMY -> lang.send(player, "upgrade.no-economy");
            case NOT_ENOUGH_MONEY -> lang.send(player, "upgrade.not-enough-money");
        }
    }

    @Override
    protected void renderContents() {
        MenuContext context = context();
        for (Map.Entry<Integer, UpgradeType> entry : slots.entrySet()) {
            UpgradeType type = entry.getValue();
            IconSet set = icons.get(type);
            if (set == null) {
                continue;
            }

            boolean allowed = viewer == null || upgrades.hasPermission(type, levelOf(type) + 1, viewer);
            boolean maxed = maxed(type);
            MenuIcon icon = layout.icon(iconNameFor(type, set, allowed, maxed));
            if (icon != null) {
                inventory.setItem(entry.getKey(),
                        icon.render(context, lang, viewer, placeholdersFor(type, allowed, maxed)));
            }
        }
    }

    /** Storage is sold either by the slot or by the page, and the two read nothing alike. */
    private String iconNameFor(UpgradeType type, IconSet set, boolean allowed, boolean maxed) {
        if (maxed) {
            return set.maxed();
        }
        if (!allowed) {
            return set.locked();
        }
        return type == UpgradeType.SLOTS && upgrades.slotModel().mode() == Upgrades.SlotMode.PAGE
                ? set.page()
                : set.normal();
    }

    private TagResolver placeholdersFor(UpgradeType type, boolean allowed, boolean maxed) {
        if (type == UpgradeType.SLOTS) {
            return slotPlaceholders(allowed, maxed);
        }
        int level = collector.upgradeLevel(type);
        Upgrades.Tier next = upgrades.next(type, level);
        return TagResolver.resolver(
                Placeholder.component("upgrade", lang.get("upgrade." + type.key() + ".name")),
                Placeholder.unparsed("level", Integer.toString(level)),
                Placeholder.unparsed("max-level", Integer.toString(upgrades.maxLevel(type))),
                Placeholder.unparsed("value", number(upgrades.value(type, level))),
                Placeholder.unparsed("next-value", next == null ? "-" : number(next.value())),
                Placeholder.unparsed("cost", next == null ? "-" : services.economy().format(next.cost())),
                Placeholder.component("reason", reason(type, allowed, maxed)),
                placeholders());
    }

    private TagResolver slotPlaceholders(boolean allowed, boolean maxed) {
        UpgradeType type = UpgradeType.SLOTS;
        int current = upgrades.slots(collector);
        int pageSize = upgrades.pageSize();
        int bulk = upgrades.purchaseSize(Upgrades.Purchase.BULK);
        return TagResolver.resolver(
                Placeholder.component("upgrade", lang.get("upgrade.slots.name")),
                Placeholder.unparsed("level", Integer.toString(current)),
                Placeholder.unparsed("max-level", Integer.toString(upgrades.maxSlots())),
                Placeholder.unparsed("value", Integer.toString(current)),
                Placeholder.unparsed("next-value", Integer.toString(Math.min(current + 1, upgrades.maxSlots()))),
                Placeholder.unparsed("pages", Integer.toString((int) Math.ceil((double) current / pageSize))),
                Placeholder.unparsed("page-size", Integer.toString(pageSize)),
                Placeholder.unparsed("bulk", Integer.toString(bulk)),
                Placeholder.unparsed("cost", cost(1)),
                Placeholder.unparsed("cost-one", cost(1)),
                Placeholder.unparsed("cost-bulk", cost(bulk)),
                Placeholder.unparsed("cost-page", cost(pageSize)),
                Placeholder.component("reason", reason(type, allowed, maxed)),
                placeholders());
    }

    private String cost(int slots) {
        int remaining = upgrades.maxSlots() - upgrades.slots(collector);
        if (remaining <= 0 || slots <= 0) {
            return "-";
        }
        return services.economy().format(upgrades.purchaseCost(Math.min(slots, remaining)));
    }

    private net.kyori.adventure.text.Component reason(UpgradeType type, boolean allowed, boolean maxed) {
        if (maxed) {
            return lang.get("upgrade.reason.maxed");
        }
        if (!allowed) {
            return lang.get("upgrade.reason.no-permission");
        }
        if (!services.economy().available()) {
            return lang.get("upgrade.reason.no-economy");
        }
        // A price can be in a currency of its own, so what matters is whether that one is there.
        String currency = currencyOf(type);
        if (!services.economy().available(currency)) {
            return lang.get("upgrade.reason.currency-missing", Placeholder.unparsed("currency",
                    currency == null || currency.isBlank() ? services.economy().defaultCurrency() : currency));
        }
        return lang.get("upgrade.reason.none");
    }

    private @Nullable String currencyOf(UpgradeType type) {
        if (type == UpgradeType.SLOTS) {
            return upgrades.slotCurrency();
        }
        Upgrades.Tier next = upgrades.next(type, collector.upgradeLevel(type));
        return next == null ? null : next.currency();
    }

    private boolean maxed(UpgradeType type) {
        return type == UpgradeType.SLOTS
                ? upgrades.slots(collector) >= upgrades.maxSlots()
                : upgrades.next(type, collector.upgradeLevel(type)) == null;
    }

    private int levelOf(UpgradeType type) {
        return type == UpgradeType.SLOTS ? upgrades.slots(collector) : collector.upgradeLevel(type);
    }

    private String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    @Override
    protected void reopen(Player player) {
        menus.openUpgrades(player, collector);
    }

    @Override
    protected MenuContext context() {
        return new MenuContext(1, 1, collector.usedSlots(), upgrades.slots(collector), collector.itemCount());
    }
}
