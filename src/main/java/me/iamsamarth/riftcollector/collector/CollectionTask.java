package me.iamsamarth.riftcollector.collector;

import me.iamsamarth.riftcollector.ability.ItemPipeline;
import me.iamsamarth.riftcollector.config.PluginConfig;
import me.iamsamarth.riftcollector.gui.MenuRegistry;
import me.iamsamarth.riftcollector.hook.ItemStackerHook;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Runs every tick and sweeps the collectors whose own interval is due, so each one can have its
 * own pickup speed from its upgrades.
 */
public final class CollectionTask implements Runnable {

    private final CollectorService collectors;
    private final CollectorItem collectorItem;
    private final MenuRegistry menus;
    private final Upgrades upgrades;
    private final ItemPipeline pipeline;
    private final ItemStackerHook stackers;
    private final PluginConfig config;
    private final Logger logger;
    private final Set<UUID> strandedReported = new HashSet<>();

    private long tick;

    public CollectionTask(CollectorService collectors, CollectorItem collectorItem, MenuRegistry menus,
                          Upgrades upgrades, ItemPipeline pipeline, ItemStackerHook stackers,
                          PluginConfig config, Logger logger) {
        this.collectors = collectors;
        this.collectorItem = collectorItem;
        this.menus = menus;
        this.upgrades = upgrades;
        this.pipeline = pipeline;
        this.stackers = stackers;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void run() {
        tick++;
        for (Collector collector : List.copyOf(collectors.index().all())) {
            if (tick < collector.nextSweepTick()) {
                continue;
            }
            World world = Bukkit.getWorld(collector.world());
            if (world == null || !world.isChunkLoaded(collector.x() >> 4, collector.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(collector.x(), collector.y(), collector.z());
            if (block.getType() != collector.material()) {
                discard(block, collector);
                continue;
            }

            int capacity = upgrades.slots(collector);
            long slotCap = upgrades.slotCapacity(collector);
            collector.nextSweepTick(tick + upgrades.sweepTicks(collector));
            reportStranded(collector, collector.compact(capacity, slotCap));

            boolean changed = false;
            for (Item item : itemsInRange(world, collector)) {
                changed |= collect(collector, item, capacity, slotCap);
            }
            if (changed) {
                menus.refresh(collector);
            }
        }
    }

    private boolean collect(Collector collector, Item item, int capacity, long slotCap) {
        if (!item.isValid() || item.getPickupDelay() == Short.MAX_VALUE) {
            return false;
        }
        ItemStack dropped = item.getItemStack();
        if (config.collection().ignoredMaterials().contains(dropped.getType())) {
            return false;
        }

        // A stacker's entity stands for more items than its own stack says, so the real count
        // comes from the hook and only what was taken is removed from the drop.
        long available = stackers.amountOf(item);
        long leftover = pipeline.process(collector, dropped, available, capacity, slotCap);
        long taken = available - leftover;
        if (taken <= 0) {
            return false;
        }
        return stackers.take(item, taken);
    }

    private List<Item> itemsInRange(World world, Collector collector) {
        int range = upgrades.range(collector, config.collection().rangeType());
        List<Item> items = new ArrayList<>();

        if (config.collection().rangeType() == PluginConfig.RangeType.RADIUS) {
            Location center = new Location(world, collector.x() + 0.5, collector.y() + 0.5, collector.z() + 0.5);
            for (Entity entity : world.getNearbyEntities(center, range, range, range)) {
                if (entity instanceof Item item) {
                    items.add(item);
                }
            }
            return items;
        }

        int chunkX = collector.x() >> 4;
        int chunkZ = collector.z() >> 4;
        for (int offsetX = -range; offsetX <= range; offsetX++) {
            for (int offsetZ = -range; offsetZ <= range; offsetZ++) {
                if (!world.isChunkLoaded(chunkX + offsetX, chunkZ + offsetZ)) {
                    continue;
                }
                for (Entity entity : world.getChunkAt(chunkX + offsetX, chunkZ + offsetZ).getEntities()) {
                    if (entity instanceof Item item) {
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    /**
     * The block is gone (explosion, world edit, ...). The contents, upgrades and settings
     * travel in the dropped collector, because a slot can hold far more items than it would be
     * safe to drop and nobody should lose an upgraded collector to a creeper.
     */
    private void discard(Block block, Collector collector) {
        menus.closeFor(collector);
        ItemStack dropped = collectorItem.create(collector);
        collectors.remove(collector);
        block.getWorld().dropItemNaturally(block.getLocation().toCenterLocation(), dropped);
    }

    private void reportStranded(Collector collector, int strandedSlots) {
        if (strandedSlots <= 0) {
            strandedReported.remove(collector.id());
            return;
        }
        if (strandedReported.add(collector.id())) {
            logger.warning("Collector at " + collector.x() + "," + collector.y() + "," + collector.z()
                    + " holds " + strandedSlots + " slot(s) beyond its capacity. They are kept in the"
                    + " database and become reachable again once the slots upgrade is raised.");
        }
    }
}
