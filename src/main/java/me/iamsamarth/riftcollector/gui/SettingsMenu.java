package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.ability.Ability;
import me.iamsamarth.riftcollector.collector.Collector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** The Settings category: one toggle per ability, each behind its own permission. */
public final class SettingsMenu extends Menu {

    private record IconSet(String enabled, String disabled, String locked) {
    }

    private final Map<Integer, Ability> slots = new HashMap<>();
    private final Map<Ability, IconSet> icons = new EnumMap<>(Ability.class);

    SettingsMenu(Menus menus, MenuLayout layout, Collector collector) {
        super(menus, layout, collector);
        readSettingSlots();
    }

    private void readSettingSlots() {
        ConfigurationSection section = layout.section("settings");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Ability ability = Ability.byKey(key);
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (ability == null || entry == null) {
                continue;
            }
            int slot = entry.getInt("slot", -1);
            if (slot < 0) {
                continue;
            }
            slots.put(slot, ability);
            icons.put(ability, new IconSet(
                    entry.getString("enabled-icon"),
                    entry.getString("disabled-icon", entry.getString("enabled-icon")),
                    entry.getString("locked-icon", entry.getString("disabled-icon"))));
        }
    }

    @Override
    protected void onMenuClick(InventoryClickEvent event, Player player) {
        Ability ability = slots.get(event.getSlot());
        if (ability == null) {
            super.onMenuClick(event, player);
            return;
        }
        if (!canUse(player)) {
            lang.send(player, "ability.no-permission");
            return;
        }
        if (!player.hasPermission(ability.permission())) {
            lang.send(player, "ability.no-permission");
            return;
        }
        if (ability == Ability.AUTO_SELL && !services.sales().usable()) {
            lang.send(player, "ability.selling-unavailable");
            return;
        }

        boolean enabled = collector.toggle(ability);
        lang.send(player, enabled ? "ability.enabled" : "ability.disabled",
                Placeholder.component("ability", lang.get("ability." + ability.key() + ".name")));
        registry.refresh(collector);
        // The hologram follows at once rather than waiting for the next refresh pass.
        services.holograms().refresh(collector);
    }

    @Override
    protected void renderContents() {
        MenuContext context = context();
        for (Map.Entry<Integer, Ability> entry : slots.entrySet()) {
            Ability ability = entry.getValue();
            IconSet set = icons.get(ability);
            if (set == null) {
                continue;
            }

            boolean allowed = allowed(ability);
            boolean enabled = collector.enabled(ability);
            String character = !allowed ? set.locked() : enabled ? set.enabled() : set.disabled();
            MenuIcon icon = layout.icon(character);
            if (icon != null) {
                inventory.setItem(entry.getKey(),
                        icon.render(context, lang, viewer, placeholdersFor(ability, enabled)));
            }
        }
    }

    private boolean allowed(Ability ability) {
        if (viewer == null) {
            return true;
        }
        if (!canUse(viewer) || !viewer.hasPermission(ability.permission())) {
            return false;
        }
        return ability != Ability.AUTO_SELL || services.sales().usable();
    }

    private TagResolver placeholdersFor(Ability ability, boolean enabled) {
        return TagResolver.resolver(
                Placeholder.component("ability", lang.get("ability." + ability.key() + ".name")),
                Placeholder.component("state", lang.get(enabled ? "ability.state-on" : "ability.state-off")),
                Placeholder.component("reason", reason(ability)),
                placeholders());
    }

    @Override
    protected void reopen(Player player) {
        menus.openSettings(player, collector);
    }

    /** Says on the item itself why a setting cannot be used, instead of leaving it a mystery. */
    private Component reason(Ability ability) {
        if (viewer != null && !canUse(viewer)) {
            return lang.get("ability.reason.not-trusted");
        }
        if (viewer != null && !viewer.hasPermission(ability.permission())) {
            return lang.get("ability.reason.no-permission");
        }
        if (ability == Ability.AUTO_SELL && !services.sales().usable()) {
            return lang.get("ability.reason.no-prices");
        }
        return lang.get("ability.reason.none");
    }

    @Override
    protected MenuContext context() {
        int capacity = services.upgrades().slots(collector);
        return new MenuContext(1, 1, collector.usedSlots(), capacity, collector.itemCount());
    }
}
