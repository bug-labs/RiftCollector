package me.iamsamarth.riftcollector.collector;

import me.iamsamarth.riftcollector.ability.Ability;
import me.iamsamarth.riftcollector.ability.FilterMode;
import me.iamsamarth.riftcollector.storage.CollectorRecord;
import me.iamsamarth.riftcollector.upgrade.UpgradeType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A placed collector and the items it holds. Storage belongs to the collector rather than to the
 * block, so every collector behaves and looks the same whatever block it was placed as, and each
 * slot counts its items instead of being limited to a vanilla stack.
 */
public final class Collector {

    private static final String FILTER_MODE_SETTING = "filter-mode";
    private static final String DISPLAY_NAME_SETTING = "display-name";
    private static final EnumSet<Ability> DEFAULT_ABILITIES = EnumSet.of(Ability.HOLOGRAM);

    private final UUID id;
    private final UUID world;
    private final int x;
    private final int y;
    private final int z;
    private final Material material;
    private final long createdAt;

    private @Nullable UUID owner;
    private @Nullable String ownerName;
    private @Nullable String displayName;
    private double earnings;
    private long itemsSold;

    private final EnumMap<UpgradeType, Integer> upgradeLevels = new EnumMap<>(UpgradeType.class);
    private final EnumSet<Ability> abilities = EnumSet.noneOf(Ability.class);
    private final Set<Material> filterMaterials = EnumSet.noneOf(Material.class);
    private final Map<UUID, String> trusted = new LinkedHashMap<>();
    private FilterMode filterMode = FilterMode.DISABLED;

    private StoredStack[] slots;
    private boolean dirty;
    private long nextSweepTick;

    private Collector(UUID id, UUID world, int x, int y, int z, Material material, long createdAt,
                      @Nullable UUID owner, @Nullable String ownerName, double earnings, long itemsSold,
                      StoredStack[] slots) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.createdAt = createdAt;
        this.owner = owner;
        this.ownerName = ownerName;
        this.earnings = earnings;
        this.itemsSold = itemsSold;
        this.slots = slots;
    }

    public static Collector create(Block block, Material material, Player owner) {
        Collector collector = new Collector(UUID.randomUUID(), block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), material, System.currentTimeMillis(),
                owner.getUniqueId(), owner.getName(), 0, 0, new StoredStack[0]);
        collector.abilities.addAll(DEFAULT_ABILITIES);
        return collector;
    }

    public static Collector unowned(UUID world, int x, int y, int z, Material material) {
        Collector collector = new Collector(UUID.randomUUID(), world, x, y, z, material,
                System.currentTimeMillis(), null, null, 0, 0, new StoredStack[0]);
        collector.abilities.addAll(DEFAULT_ABILITIES);
        return collector;
    }

    /** Rebuilds a collector from its stored row; null when the material no longer exists. */
    public static @Nullable Collector restore(CollectorRecord record) {
        Material material = Material.matchMaterial(record.material());
        if (material == null) {
            return null;
        }

        int size = 0;
        for (CollectorRecord.SlotRecord slot : record.slots()) {
            size = Math.max(size, slot.slot() + 1);
        }
        StoredStack[] slots = new StoredStack[size];
        for (CollectorRecord.SlotRecord slot : record.slots()) {
            slots[slot.slot()] = new StoredStack(ItemStack.deserializeBytes(slot.item()), slot.amount());
        }

        Collector collector = new Collector(record.id(), record.world(), record.x(), record.y(), record.z(),
                material, record.createdAt(), record.owner(), record.ownerName(),
                record.earnings(), record.itemsSold(), slots);
        record.upgrades().forEach((key, level) -> {
            UpgradeType type = UpgradeType.byKey(key);
            if (type != null) {
                collector.upgradeLevels.put(type, level);
            }
        });
        // Defaults first, so a stored "false" still wins and a key that was never stored keeps
        // whatever this version defaults to.
        collector.abilities.addAll(DEFAULT_ABILITIES);
        record.settings().forEach((key, value) -> {
            Ability ability = Ability.byKey(key);
            if (ability != null) {
                if (Boolean.parseBoolean(value)) {
                    collector.abilities.add(ability);
                } else {
                    collector.abilities.remove(ability);
                }
            } else if (FILTER_MODE_SETTING.equals(key)) {
                collector.filterMode = FilterMode.parse(value);
            } else if (DISPLAY_NAME_SETTING.equals(key)) {
                collector.displayName = value == null || value.isBlank() ? null : value;
            }
        });
        for (String name : record.filter()) {
            Material filtered = Material.matchMaterial(name);
            if (filtered != null) {
                collector.filterMaterials.add(filtered);
            }
        }
        record.trusted().forEach((id, name) -> collector.trusted.put(UUID.fromString(id), name));
        collector.markClean();
        return collector;
    }

    /** Snapshots the collector for the database; must run on the main thread. */
    public CollectorRecord toRecord() {
        List<CollectorRecord.SlotRecord> stored = new ArrayList<>();
        for (int index = 0; index < slots.length; index++) {
            StoredStack stack = slots[index];
            if (stack != null && stack.amount() > 0) {
                stored.add(new CollectorRecord.SlotRecord(
                        index, stack.prototype().serializeAsBytes(), stack.amount()));
            }
        }
        Map<String, Integer> upgrades = new HashMap<>();
        upgradeLevels.forEach((type, level) -> upgrades.put(type.key(), level));

        Map<String, String> settings = new HashMap<>();
        for (Ability ability : Ability.values()) {
            settings.put(ability.key(), Boolean.toString(abilities.contains(ability)));
        }
        settings.put(FILTER_MODE_SETTING, filterMode.name());
        if (displayName != null) {
            settings.put(DISPLAY_NAME_SETTING, displayName);
        }

        Set<String> filter = new HashSet<>();
        for (Material filtered : filterMaterials) {
            filter.add(filtered.name());
        }

        Map<String, String> trustedByName = new HashMap<>();
        trusted.forEach((player, name) -> trustedByName.put(player.toString(), name));

        return new CollectorRecord(id, world, x, y, z, owner, ownerName, material.name(),
                earnings, itemsSold, createdAt, stored, upgrades, settings, filter, trustedByName);
    }

    public UUID id() {
        return id;
    }

    public UUID world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Material material() {
        return material;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    /** Players the owner let in, by id and by the name to show them under. */
    public Map<UUID, String> trusted() {
        return Collections.unmodifiableMap(trusted);
    }

    public boolean isTrusted(UUID player) {
        return trusted.containsKey(player);
    }

    public void trust(UUID player, String name) {
        trusted.put(player, name);
        dirty = true;
    }

    public void untrust(UUID player) {
        if (trusted.remove(player) != null) {
            dirty = true;
        }
    }

    public @Nullable String ownerName() {
        return ownerName;
    }

    /** The name the owner gave this collector, or null while it is still just "Collector". */
    public @Nullable String displayName() {
        return displayName;
    }

    public void displayName(@Nullable String name) {
        String trimmed = name == null ? null : name.trim();
        this.displayName = trimmed == null || trimmed.isEmpty() ? null : trimmed;
        dirty = true;
    }

    public void owner(@Nullable UUID owner, @Nullable String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
        dirty = true;
    }

    public boolean isOwner(Player player) {
        return owner != null && owner.equals(player.getUniqueId());
    }

    public double earnings() {
        return earnings;
    }

    public long itemsSold() {
        return itemsSold;
    }

    public void addSale(double amount, long items) {
        earnings += amount;
        itemsSold += items;
        dirty = true;
    }

    public @Nullable StoredStack stack(int index) {
        return index >= 0 && index < slots.length ? slots[index] : null;
    }

    /** Stores what fits and returns how many items were left over. */
    public long insert(ItemStack incoming, int capacity, long slotCap) {
        return store(incoming, incoming.getAmount(), capacity, slotCap);
    }

    /**
     * Stores a count of one kind of item. A stacker can put far more on the ground than an
     * ItemStack is allowed to say, so the amount travels separately from the item.
     */
    public long insert(ItemStack prototype, long amount, int capacity, long slotCap) {
        return store(prototype, amount, capacity, slotCap);
    }

    /**
     * Takes up to {@code count} items out of a slot, never more than one vanilla stack, and
     * returns them as a real stack ready to be handed to a player.
     */
    public @Nullable ItemStack withdraw(int index, int count) {
        StoredStack stored = stack(index);
        if (stored == null || count <= 0) {
            return null;
        }
        int taken = (int) Math.min(Math.min(count, stored.maxStackSize()), stored.amount());
        if (taken <= 0) {
            return null;
        }
        long left = stored.amount() - taken;
        slots[index] = left <= 0 ? null : stored.minus(taken);
        dirty = true;
        if (left <= 0) {
            closeGaps();
        }
        return stored.copy(taken);
    }

    /**
     * Pulls everything down into the lowest free slots. Emptying a slot in the middle would
     * otherwise leave a hole that the menu shows as a gap for as long as the collector lives.
     */
    private void closeGaps() {
        int target = 0;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                continue;
            }
            if (index != target) {
                slots[target] = slots[index];
                slots[index] = null;
                dirty = true;
            }
            target++;
        }
    }

    /**
     * Moves everything stored beyond the capacity back into reach. Whatever still does not fit
     * stays where it is rather than being destroyed, and the returned count says how many slots
     * are stranded until the capacity goes up again.
     */
    public int compact(int capacity, long slotCap) {
        if (slots.length <= capacity) {
            return 0;
        }
        int stranded = 0;
        for (int index = capacity; index < slots.length; index++) {
            StoredStack stack = slots[index];
            if (stack == null) {
                continue;
            }
            long leftover = store(stack.prototype(), stack.amount(), capacity, slotCap);
            if (leftover <= 0) {
                slots[index] = null;
                dirty = true;
            } else {
                slots[index] = new StoredStack(stack.prototype(), leftover);
                stranded++;
            }
        }
        if (stranded == 0) {
            // Nothing is left above the capacity, so stop carrying the empty tail around.
            slots = Arrays.copyOf(slots, capacity);
            dirty = true;
        }
        return stranded;
    }

    public void fill(List<StoredStack> contents) {
        for (StoredStack stack : contents) {
            if (stack.amount() <= 0) {
                continue;
            }
            int index = firstFree();
            if (index < 0) {
                return;
            }
            ensureCapacity(index + 1);
            slots[index] = stack;
            dirty = true;
        }
    }

    /** How many slots the storage array currently has, gaps included. */
    public int slotCount() {
        return slots.length;
    }

    public int usedSlots() {
        int used = 0;
        for (StoredStack stack : slots) {
            if (stack != null) {
                used++;
            }
        }
        return used;
    }

    public long itemCount() {
        long count = 0;
        for (StoredStack stack : slots) {
            if (stack != null) {
                count += stack.amount();
            }
        }
        return count;
    }

    /** Empties the listed slots, used when the contents are sold in one go. */
    public void clear(List<Integer> indexes) {
        for (int index : indexes) {
            if (index >= 0 && index < slots.length) {
                slots[index] = null;
                dirty = true;
            }
        }
        closeGaps();
    }

    public List<StoredStack> contents() {
        List<StoredStack> contents = new ArrayList<>();
        for (StoredStack stack : slots) {
            if (stack != null && stack.amount() > 0) {
                contents.add(stack);
            }
        }
        return contents;
    }

    public int upgradeLevel(UpgradeType type) {
        return upgradeLevels.getOrDefault(type, 0);
    }

    public void upgradeLevel(UpgradeType type, int level) {
        upgradeLevels.put(type, Math.max(0, level));
        dirty = true;
    }

    public boolean enabled(Ability ability) {
        return abilities.contains(ability);
    }

    /** Sets an ability outright, used when a replaced collector brings its settings back. */
    public void ability(Ability ability, boolean enabled) {
        if (enabled ? abilities.add(ability) : abilities.remove(ability)) {
            dirty = true;
        }
    }

    public boolean toggle(Ability ability) {
        boolean enabled = !abilities.remove(ability);
        if (enabled) {
            abilities.add(ability);
        }
        dirty = true;
        return enabled;
    }

    public FilterMode filterMode() {
        return filterMode;
    }

    public void filterMode(FilterMode mode) {
        if (filterMode != mode) {
            filterMode = mode;
            dirty = true;
        }
    }

    public FilterMode cycleFilterMode() {
        filterMode = filterMode.next();
        dirty = true;
        return filterMode;
    }

    public Set<Material> filterMaterials() {
        return Collections.unmodifiableSet(filterMaterials);
    }

    public void addFilter(Material material) {
        if (filterMaterials.add(material)) {
            dirty = true;
        }
    }

    public void removeFilter(Material material) {
        if (filterMaterials.remove(material)) {
            dirty = true;
        }
    }

    /** Whether the filter lets this item in at all. */
    public boolean accepts(Material material) {
        return switch (filterMode) {
            case DISABLED -> true;
            case WHITELIST -> filterMaterials.contains(material);
            case BLACKLIST -> !filterMaterials.contains(material);
        };
    }

    /** The tick this collector may sweep again; kept in memory only. */
    public long nextSweepTick() {
        return nextSweepTick;
    }

    public void nextSweepTick(long tick) {
        this.nextSweepTick = tick;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    private long store(ItemStack prototype, long amount, int capacity, long slotCap) {
        long remaining = amount;
        int limit = Math.min(capacity, ensureCapacity(capacity));

        for (int index = 0; index < limit && remaining > 0; index++) {
            StoredStack stored = slots[index];
            if (stored == null || !stored.matches(prototype)) {
                continue;
            }
            long space = slotCap - stored.amount();
            if (space <= 0) {
                continue;
            }
            long moved = Math.min(space, remaining);
            slots[index] = stored.plus(moved);
            remaining -= moved;
            dirty = true;
        }

        for (int index = 0; index < limit && remaining > 0; index++) {
            if (slots[index] != null) {
                continue;
            }
            long moved = Math.min(slotCap, remaining);
            slots[index] = new StoredStack(prototype, moved);
            remaining -= moved;
            dirty = true;
        }
        return remaining;
    }

    private int firstFree() {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                return index;
            }
        }
        return slots.length;
    }

    private int ensureCapacity(int capacity) {
        if (slots.length < capacity) {
            slots = Arrays.copyOf(slots, capacity);
            dirty = true;
        }
        return slots.length;
    }
}
