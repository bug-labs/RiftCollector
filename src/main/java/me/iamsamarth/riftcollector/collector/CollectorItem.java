package me.iamsamarth.riftcollector.collector;

import me.iamsamarth.riftcollector.ability.Ability;
import me.iamsamarth.riftcollector.ability.FilterMode;
import me.iamsamarth.riftcollector.lang.Lang;
import me.iamsamarth.riftcollector.upgrade.UpgradeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The placeable collector item. A collector that leaves the ground keeps everything it had
 * inside the item it becomes: its contents, because a slot can hold a million items and
 * dropping those would mean thousands of entities, and the upgrades, settings and name it was
 * bought up to, because losing those to a misplaced pickaxe is not a sensible way to lose them.
 */
public final class CollectorItem {

    /**
     * Everything a dropped collector remembers about the one it came from. {@code fromCollector}
     * tells a dropped collector apart from a fresh item out of /collector give, where no setting
     * was recorded and the defaults have to stand.
     */
    public record Carried(boolean fromCollector,
                          List<StoredStack> contents,
                          Map<UpgradeType, Integer> upgrades,
                          Set<Ability> abilities,
                          @Nullable FilterMode filterMode,
                          Set<Material> filter,
                          @Nullable String displayName) {

        static final Carried NONE =
                new Carried(false, List.of(), Map.of(), Set.of(), null, Set.of(), null);
    }

    private static final String FILTER_MODE_ENTRY = "filter-mode";
    private static final String FILTER_LIST_ENTRY = "filter";

    private final Plugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey slotsKey;
    private final NamespacedKey itemsKey;
    private final NamespacedKey amountsKey;
    private final NamespacedKey upgradesKey;
    private final NamespacedKey settingsKey;
    private final NamespacedKey nameKey;
    private final Lang lang;

    public CollectorItem(Plugin plugin, Lang lang) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "collector");
        this.slotsKey = new NamespacedKey(plugin, "contents_slots");
        this.itemsKey = new NamespacedKey(plugin, "contents_items");
        this.amountsKey = new NamespacedKey(plugin, "contents_amounts");
        this.upgradesKey = new NamespacedKey(plugin, "upgrades");
        this.settingsKey = new NamespacedKey(plugin, "settings");
        this.nameKey = new NamespacedKey(plugin, "display_name");
        this.lang = lang;
    }

    /** The material is passed in so a broken collector drops as the block it was placed as. */
    public ItemStack create(int amount, Material material) {
        ItemStack stack = new ItemStack(material, amount);
        stack.editMeta(meta -> {
            meta.displayName(lang.get("item.name"));
            meta.lore(lang.getList("item.lore"));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        });
        return stack;
    }

    /** The item a collector becomes when it is taken out of the ground. */
    public ItemStack create(Collector collector) {
        ItemStack stack = create(1, collector.material());
        List<StoredStack> contents = collector.contents();
        stack.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            writeContents(data, contents);
            writeUpgrades(data, collector);
            writeSettings(data, collector);
            if (collector.displayName() != null) {
                data.set(nameKey, PersistentDataType.STRING, collector.displayName());
            }
            meta.lore(describe(collector, contents));
        });
        return stack;
    }

    /** What a dropped collector was carrying; empty for one that came from the command. */
    public Carried carried(ItemStack stack) {
        if (!isCollector(stack)) {
            return Carried.NONE;
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        // 2.0.0-beta.1 stored only the contents, so an item from then has no settings to put back.
        boolean recorded = data.has(settingsKey, PersistentDataType.TAG_CONTAINER);
        return new Carried(recorded, readContents(data), readUpgrades(data), readAbilities(data),
                readFilterMode(data), readFilter(data),
                data.get(nameKey, PersistentDataType.STRING));
    }

    public boolean isCollector(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN);
    }

    private void writeContents(PersistentDataContainer data, List<StoredStack> contents) {
        if (contents.isEmpty()) {
            return;
        }
        int[] slots = new int[contents.size()];
        long[] amounts = new long[contents.size()];
        List<ItemStack> prototypes = new ArrayList<>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            StoredStack stored = contents.get(index);
            slots[index] = index;
            amounts[index] = stored.amount();
            prototypes.add(stored.prototype());
        }
        data.set(slotsKey, PersistentDataType.INTEGER_ARRAY, slots);
        data.set(itemsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(prototypes));
        data.set(amountsKey, PersistentDataType.LONG_ARRAY, amounts);
    }

    private List<StoredStack> readContents(PersistentDataContainer data) {
        byte[] itemBytes = data.get(itemsKey, PersistentDataType.BYTE_ARRAY);
        long[] amounts = data.get(amountsKey, PersistentDataType.LONG_ARRAY);
        if (itemBytes == null || itemBytes.length == 0 || amounts == null || amounts.length == 0) {
            return List.of();
        }

        ItemStack[] prototypes = ItemStack.deserializeItemsFromBytes(itemBytes);
        List<StoredStack> contents = new ArrayList<>(prototypes.length);
        for (int index = 0; index < prototypes.length && index < amounts.length; index++) {
            if (amounts[index] > 0) {
                contents.add(new StoredStack(prototypes[index], amounts[index]));
            }
        }
        return contents;
    }

    private void writeUpgrades(PersistentDataContainer data, Collector collector) {
        PersistentDataContainer levels = data.getAdapterContext().newPersistentDataContainer();
        for (UpgradeType type : UpgradeType.values()) {
            int level = collector.upgradeLevel(type);
            if (level > 0) {
                levels.set(entryKey(type.key()), PersistentDataType.INTEGER, level);
            }
        }
        if (!levels.isEmpty()) {
            data.set(upgradesKey, PersistentDataType.TAG_CONTAINER, levels);
        }
    }

    private Map<UpgradeType, Integer> readUpgrades(PersistentDataContainer data) {
        PersistentDataContainer levels = data.get(upgradesKey, PersistentDataType.TAG_CONTAINER);
        if (levels == null) {
            return Map.of();
        }
        Map<UpgradeType, Integer> upgrades = new EnumMap<>(UpgradeType.class);
        for (UpgradeType type : UpgradeType.values()) {
            Integer level = levels.get(entryKey(type.key()), PersistentDataType.INTEGER);
            if (level != null && level > 0) {
                upgrades.put(type, level);
            }
        }
        return upgrades;
    }

    private void writeSettings(PersistentDataContainer data, Collector collector) {
        PersistentDataContainer settings = data.getAdapterContext().newPersistentDataContainer();
        for (Ability ability : Ability.values()) {
            settings.set(entryKey(ability.key()), PersistentDataType.BOOLEAN, collector.enabled(ability));
        }
        settings.set(entryKey(FILTER_MODE_ENTRY), PersistentDataType.STRING, collector.filterMode().name());
        if (!collector.filterMaterials().isEmpty()) {
            List<String> names = new ArrayList<>(collector.filterMaterials().size());
            collector.filterMaterials().forEach(material -> names.add(material.name()));
            settings.set(entryKey(FILTER_LIST_ENTRY), PersistentDataType.STRING, String.join(",", names));
        }
        data.set(settingsKey, PersistentDataType.TAG_CONTAINER, settings);
    }

    private Set<Ability> readAbilities(PersistentDataContainer data) {
        PersistentDataContainer settings = data.get(settingsKey, PersistentDataType.TAG_CONTAINER);
        if (settings == null) {
            return Set.of();
        }
        Set<Ability> enabled = EnumSet.noneOf(Ability.class);
        for (Ability ability : Ability.values()) {
            if (Boolean.TRUE.equals(settings.get(entryKey(ability.key()), PersistentDataType.BOOLEAN))) {
                enabled.add(ability);
            }
        }
        return enabled;
    }

    private @Nullable FilterMode readFilterMode(PersistentDataContainer data) {
        PersistentDataContainer settings = data.get(settingsKey, PersistentDataType.TAG_CONTAINER);
        String mode = settings == null
                ? null
                : settings.get(entryKey(FILTER_MODE_ENTRY), PersistentDataType.STRING);
        return mode == null ? null : FilterMode.parse(mode);
    }

    private Set<Material> readFilter(PersistentDataContainer data) {
        PersistentDataContainer settings = data.get(settingsKey, PersistentDataType.TAG_CONTAINER);
        String joined = settings == null
                ? null
                : settings.get(entryKey(FILTER_LIST_ENTRY), PersistentDataType.STRING);
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        Set<Material> filter = EnumSet.noneOf(Material.class);
        for (String name : joined.split(",")) {
            Material material = Material.matchMaterial(name.trim());
            if (material != null) {
                filter.add(material);
            }
        }
        return filter;
    }

    /** Tells the holder what is inside, so a stack in a chest is not a mystery box. */
    private List<Component> describe(Collector collector, List<StoredStack> contents) {
        List<Component> lore = new ArrayList<>(lang.getList("item.lore"));
        long total = 0;
        for (StoredStack stored : contents) {
            total += stored.amount();
        }

        List<Component> carried = new ArrayList<>();
        if (collector.displayName() != null) {
            carried.add(lang.get("item.named", Placeholder.unparsed("name", collector.displayName())));
        }
        if (total > 0) {
            carried.add(lang.get("item.contents",
                    Placeholder.unparsed("items", String.format(Locale.ROOT, "%,d", total))));
        }
        int upgraded = 0;
        for (UpgradeType type : UpgradeType.values()) {
            if (collector.upgradeLevel(type) > 0) {
                upgraded++;
            }
        }
        if (upgraded > 0) {
            carried.add(lang.get("item.upgrades", Placeholder.unparsed("count", Integer.toString(upgraded))));
        }

        if (!carried.isEmpty()) {
            lore.add(Component.empty());
            lore.addAll(carried);
            lore.add(lang.get("item.restores"));
        }
        return lore;
    }

    /**
     * Keys inside the nested containers are namespaced too, and an upgrade or ability key is
     * already lower case with hyphens, which is exactly what a key may contain.
     */
    private NamespacedKey entryKey(String name) {
        return new NamespacedKey(plugin, name);
    }
}
