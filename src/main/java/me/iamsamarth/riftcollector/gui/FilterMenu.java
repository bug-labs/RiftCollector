package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.ability.FilterMode;
import me.iamsamarth.riftcollector.collector.Collector;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The filter list. Content slots show the filtered materials, shift clicking an item from the
 * player's inventory adds it, clicking one in the list removes it, and one icon cycles the mode.
 */
public final class FilterMenu extends Menu {

    private final Map<FilterMode, String> modeIcons = new EnumMap<>(FilterMode.class);
    private int modeSlot = -1;
    private int page = 1;

    FilterMenu(Menus menus, MenuLayout layout, Collector collector) {
        super(menus, layout, collector);
        readModeSlot();
    }

    private void readModeSlot() {
        ConfigurationSection section = layout.section("mode");
        if (section == null) {
            return;
        }
        modeSlot = section.getInt("slot", -1);
        modeIcons.put(FilterMode.DISABLED, section.getString("disabled-icon"));
        modeIcons.put(FilterMode.WHITELIST, section.getString("whitelist-icon"));
        modeIcons.put(FilterMode.BLACKLIST, section.getString("blacklist-icon"));
    }

    @Override
    protected void onMenuClick(InventoryClickEvent event, Player player) {
        if (!canUse(player)) {
            lang.send(player, "ability.no-permission");
            return;
        }
        if (event.getSlot() == modeSlot) {
            FilterMode mode = collector.cycleFilterMode();
            lang.send(player, "filter.mode-changed",
                    Placeholder.component("mode", lang.get("filter.mode." + mode.key())));
            registry.refresh(collector);
            return;
        }

        int contentIndex = layout.contentIndexOf(event.getSlot());
        if (contentIndex < 0) {
            super.onMenuClick(event, player);
            return;
        }

        List<Material> filtered = filtered();
        int index = (page - 1) * Math.max(1, layout.contentSlotCount()) + contentIndex;
        if (index >= filtered.size()) {
            return;
        }
        Material removed = filtered.get(index);
        collector.removeFilter(removed);
        lang.send(player, "filter.removed", Placeholder.unparsed("item", removed.name()));
        registry.refresh(collector);
    }

    @Override
    protected void onPlayerInventoryClick(InventoryClickEvent event, Player player) {
        if (!event.isShiftClick()) {
            return;
        }
        event.setCancelled(true);
        if (!canUse(player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        collector.addFilter(clicked.getType());
        lang.send(player, "filter.added", Placeholder.unparsed("item", clicked.getType().name()));
        registry.refresh(collector);
    }

    @Override
    protected void onAction(MenuIcon.Action action, Player player) {
        switch (action) {
            case NEXT_PAGE -> turnTo(page + 1);
            case PREVIOUS_PAGE -> turnTo(page - 1);
            default -> super.onAction(action, player);
        }
    }

    @Override
    protected void renderContents() {
        if (modeSlot >= 0) {
            MenuIcon icon = layout.icon(modeIcons.get(collector.filterMode()));
            if (icon != null) {
                inventory.setItem(modeSlot, icon.render(context(), lang, viewer, placeholders()));
            }
        }

        List<Material> filtered = filtered();
        int[] slots = layout.contentSlots();
        for (int index = 0; index < slots.length; index++) {
            int listIndex = (page - 1) * slots.length + index;
            inventory.setItem(slots[index],
                    listIndex < filtered.size() ? new ItemStack(filtered.get(listIndex)) : null);
        }
    }

    @Override
    protected MenuContext context() {
        int size = filtered().size();
        int pageSize = Math.max(1, layout.contentSlotCount());
        return new MenuContext(page, Math.max(1, (int) Math.ceil((double) size / pageSize)), size, size, size);
    }

    @Override
    protected TagResolver placeholders() {
        return TagResolver.resolver(
                Placeholder.component("mode", lang.get("filter.mode." + collector.filterMode().key())),
                Placeholder.unparsed("filtered", Integer.toString(collector.filterMaterials().size())),
                super.placeholders());
    }

    @Override
    protected void reopen(Player player) {
        menus.openFilter(player, collector);
    }

    private void turnTo(int target) {
        if (target < 1 || target > context().totalPages() || target == page) {
            return;
        }
        page = target;
        render();
    }

    private List<Material> filtered() {
        List<Material> materials = new ArrayList<>(collector.filterMaterials());
        materials.sort(null);
        return materials;
    }
}
