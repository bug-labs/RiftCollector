package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.collector.StoredStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main collector menu. Content slots are a drawing of the collector's storage rather than
 * real inventory slots, because a slot can hold more items than vanilla can represent.
 */
public final class CollectorMenu extends Menu {

    private int page = 1;

    CollectorMenu(Menus menus, MenuLayout layout, Collector collector) {
        super(menus, layout, collector);
    }

    @Override
    protected void onMenuClick(InventoryClickEvent event, Player player) {
        int contentIndex = layout.contentIndexOf(event.getSlot());
        if (contentIndex < 0) {
            super.onMenuClick(event, player);
            return;
        }
        if (!canUse(player)) {
            return;
        }
        int index = storageIndex(contentIndex);
        if (index >= capacity()) {
            lang.send(player, "collector.slot-locked");
            return;
        }

        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir()) {
            depositCursor(player, cursor);
        } else {
            take(layout.contentAction(event.getClick()), player, index);
        }
    }

    @Override
    protected void onPlayerInventoryClick(InventoryClickEvent event, Player player) {
        // Vanilla would shift items into the first free slot of the menu, border included.
        if (event.isShiftClick()) {
            event.setCancelled(true);
            if (canUse(player)) {
                depositClicked(event);
            }
        }
    }

    @Override
    protected void onAction(MenuIcon.Action action, Player player) {
        switch (action) {
            case NEXT_PAGE -> turnTo(page + 1, player);
            case PREVIOUS_PAGE -> turnTo(page - 1, player);
            default -> super.onAction(action, player);
        }
    }

    @Override
    protected void renderContents() {
        MenuIcon locked = layout.icon(layout.option("locked-slot-icon"));
        MenuContext context = context();
        int capacity = capacity();
        int[] slots = layout.contentSlots();

        for (int index = 0; index < slots.length; index++) {
            int storageIndex = storageIndex(index);
            if (storageIndex >= capacity) {
                // Slots the collector has not unlocked yet, shown only when the page is partly usable.
                inventory.setItem(slots[index],
                        locked == null ? null : locked.render(context, lang, viewer, placeholders()));
                continue;
            }
            StoredStack stored = collector.stack(storageIndex);
            inventory.setItem(slots[index], stored == null ? null : display(stored));
        }
    }

    @Override
    protected MenuContext context() {
        return new MenuContext(page, totalPages(), collector.usedSlots(), capacity(), collector.itemCount());
    }

    private int capacity() {
        return services.upgrades().slots(collector);
    }

    private long slotCap() {
        return services.upgrades().slotCapacity(collector);
    }

    private int pageSize() {
        return Math.max(1, layout.contentSlotCount());
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil((double) capacity() / pageSize()));
    }

    private void turnTo(int target, Player player) {
        if (target < 1 || target > totalPages() || target == page) {
            return;
        }
        page = target;
        registry.closed(this);
        build();
        registry.opened(this);
        player.openInventory(inventory);
    }

    private void take(StackAction action, Player player, int index) {
        switch (action) {
            case TAKE_ONE -> give(player, collector.withdraw(index, 1));
            case TAKE_STACK -> give(player, collector.withdraw(index, Integer.MAX_VALUE));
            case FILL_INVENTORY -> fill(player, index);
            case NONE -> {
                return;
            }
        }
        registry.refresh(collector);
        services.holograms().refresh(collector);
    }

    private void fill(Player player, int index) {
        while (true) {
            ItemStack stack = collector.withdraw(index, Integer.MAX_VALUE);
            if (stack == null) {
                return;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(rest -> collector.insert(rest, capacity(), slotCap()));
                return;
            }
        }
    }

    private void give(Player player, ItemStack stack) {
        if (stack == null) {
            return;
        }
        // Anything that does not fit goes back into the collector rather than onto the floor.
        player.getInventory().addItem(stack).values()
                .forEach(rest -> collector.insert(rest, capacity(), slotCap()));
    }

    private void depositCursor(Player player, ItemStack cursor) {
        long leftover = collector.insert(cursor, capacity(), slotCap());
        if (leftover >= cursor.getAmount()) {
            return;
        }
        if (leftover <= 0) {
            player.setItemOnCursor(null);
        } else {
            ItemStack rest = cursor.clone();
            rest.setAmount((int) leftover);
            player.setItemOnCursor(rest);
        }
        registry.refresh(collector);
        services.holograms().refresh(collector);
    }

    private void depositClicked(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        long leftover = collector.insert(clicked, capacity(), slotCap());
        if (leftover >= clicked.getAmount()) {
            return;
        }
        if (leftover <= 0) {
            event.setCurrentItem(null);
        } else {
            ItemStack rest = clicked.clone();
            rest.setAmount((int) leftover);
            event.setCurrentItem(rest);
        }
        registry.refresh(collector);
        services.holograms().refresh(collector);
    }

    /** The stored stack as the client can show it: a normal stack, with the real count in the lore. */
    private ItemStack display(StoredStack stored) {
        ItemStack stack = stored.copy((int) Math.min(stored.amount(), stored.maxStackSize()));
        List<String> extraLore = layout.contentLore();
        if (extraLore.isEmpty()) {
            return stack;
        }
        stack.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            for (String line : extraLore) {
                lore.add(lang.parse(viewer, line,
                        Placeholder.unparsed("amount", String.format(Locale.ROOT, "%,d", stored.amount())),
                        Placeholder.unparsed("stack-cap", String.format(Locale.ROOT, "%,d", slotCap()))));
            }
            meta.lore(lore);
        });
        return stack;
    }

    private int storageIndex(int contentIndex) {
        return (page - 1) * pageSize() + contentIndex;
    }
}
