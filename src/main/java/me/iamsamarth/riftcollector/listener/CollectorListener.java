package me.iamsamarth.riftcollector.listener;

import me.iamsamarth.riftcollector.RiftCollector;
import me.iamsamarth.riftcollector.ability.Ability;
import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.collector.CollectorItem;
import me.iamsamarth.riftcollector.collector.CollectorService;

import me.iamsamarth.riftcollector.lang.Lang;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class CollectorListener implements Listener {

    private static final String BYPASS_PERMISSION = "riftcollector.bypass";

    private final RiftCollector plugin;
    private final CollectorService collectors;
    private final CollectorItem collectorItem;
    private final Lang lang;

    public CollectorListener(RiftCollector plugin, CollectorService collectors,
                             CollectorItem collectorItem, Lang lang) {
        this.plugin = plugin;
        this.collectors = collectors;
        this.collectorItem = collectorItem;
        this.lang = lang;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!collectorItem.isCollector(event.getItemInHand())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("riftcollector.place")) {
            event.setCancelled(true);
            lang.send(player, "collector.no-permission");
            return;
        }

        Block block = event.getBlockPlaced();
        int limit = plugin.config().limits().collectorsPerChunk();
        if (collectors.index().countIn(block.getChunk()) >= limit) {
            event.setCancelled(true);
            lang.send(player, "collector.chunk-limit", Placeholder.unparsed("limit", Integer.toString(limit)));
            return;
        }

        Collector collector = collectors.create(block, block.getType(), player);
        // A collector that left the ground carries its contents, upgrades and settings in the
        // item it became, so putting it back down picks up where it left off.
        restore(collector, collectorItem.carried(event.getItemInHand()));
        plugin.holograms().refresh(collector);
        lang.send(player, "collector.placed");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Collector collector = collectors.get(block);
        if (collector == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!canManage(player, collector)) {
            event.setCancelled(true);
            sendNotOwner(player, collector);
            return;
        }
        // A collector is only removable while empty, so its contents can never ride along in an
        // item or flood the ground as entities.
        if (collector.itemCount() > 0) {
            event.setCancelled(true);
            lang.send(player, "collector.not-empty");
            return;
        }

        plugin.menus().registry().closeFor(collector);
        boolean dropCollector = event.isDropItems() && player.getGameMode() != GameMode.CREATIVE;
        ItemStack dropped = collectorItem.create(collector);
        collectors.remove(collector);

        event.setDropItems(false);
        if (dropCollector) {
            block.getWorld().dropItemNaturally(block.getLocation().toCenterLocation(), dropped);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        // Sneaking with something in hand is how players build against a block.
        if (player.isSneaking() && event.getItem() != null) {
            return;
        }
        Collector collector = collectors.get(block);
        if (collector == null) {
            return;
        }

        event.setCancelled(true);
        if (!canUse(player, collector)) {
            sendNotOwner(player, collector);
            return;
        }
        plugin.menus().openCollector(player, collector);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (belongsToCollector(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent event) {
        if (belongsToCollector(event.getSource()) || belongsToCollector(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (belongsToCollector(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    /**
     * Puts back what the item remembers. Nothing is applied for an item that never was a
     * collector, so one out of /collector give still starts on the defaults.
     */
    private void restore(Collector collector, CollectorItem.Carried carried) {
        collector.fill(carried.contents());
        if (!carried.fromCollector()) {
            return;
        }
        carried.upgrades().forEach(collector::upgradeLevel);
        for (Ability ability : Ability.values()) {
            collector.ability(ability, carried.abilities().contains(ability));
        }
        if (carried.filterMode() != null) {
            collector.filterMode(carried.filterMode());
        }
        carried.filter().forEach(collector::addFilter);
        collector.displayName(carried.displayName());
    }

    /** Opening and using: the owner, anyone they trusted, and staff. */
    private boolean canUse(Player player, Collector collector) {
        return collector.isOwner(player) || collector.isTrusted(player.getUniqueId())
                || player.hasPermission(BYPASS_PERMISSION);
    }

    /** Removing the collector: the owner and staff only. */
    private boolean canManage(Player player, Collector collector) {
        return collector.isOwner(player) || player.hasPermission(BYPASS_PERMISSION);
    }

    private void sendNotOwner(Player player, Collector collector) {
        String ownerName = collector.ownerName();
        lang.send(player, "collector.not-owner", ownerName == null
                ? Placeholder.component("owner", lang.get("collector.unknown-owner"))
                : Placeholder.unparsed("owner", ownerName));
    }

    /** Collector blocks are inert as vanilla containers; everything goes through their storage. */
    private boolean belongsToCollector(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            return isCollector(doubleChest.getLeftSide()) || isCollector(doubleChest.getRightSide());
        }
        return isCollector(holder);
    }

    private boolean isCollector(@Nullable InventoryHolder holder) {
        return holder instanceof BlockState state && collectors.get(state.getBlock()) != null;
    }
}
