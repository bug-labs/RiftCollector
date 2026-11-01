package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.lang.Lang;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** A single configured menu item, with the condition deciding whether it is shown. */
public record MenuIcon(Material material,
                       int amount,
                       @Nullable String name,
                       List<String> lore,
                       boolean hideTooltip,
                       boolean glow,
                       @Nullable NamespacedKey itemModel,
                       Action action,
                       Condition condition,
                       @Nullable MenuIcon fallback) {

    public enum Action {
        NONE, NEXT_PAGE, PREVIOUS_PAGE, CLOSE, OPEN_UPGRADES, OPEN_SETTINGS, OPEN_FILTER, OPEN_TRUST,
        BACK, SELL_ALL, PICKUP, RENAME, TRUST_ADD;

        static Action parse(String raw, Logger logger) {
            Action action = read(raw, Action.class);
            if (action == null) {
                logger.warning("Unknown menu action '" + raw + "', treating it as none.");
                return NONE;
            }
            return action;
        }
    }

    public record Condition(Type type, boolean negated) {

        public enum Type {
            ALWAYS, HAS_NEXT_PAGE, HAS_PREVIOUS_PAGE, HAS_ITEMS, IS_EMPTY, IS_FULL
        }

        static final Condition ALWAYS = new Condition(Type.ALWAYS, false);

        static Condition parse(String raw, Logger logger) {
            boolean negated = raw.startsWith("!");
            Type type = read(negated ? raw.substring(1) : raw, Type.class);
            if (type == null) {
                logger.warning("Unknown menu condition '" + raw + "', showing the item always.");
                return ALWAYS;
            }
            return new Condition(type, negated);
        }

        public boolean test(MenuContext context) {
            boolean met = switch (type) {
                case ALWAYS -> true;
                case HAS_NEXT_PAGE -> context.hasNextPage();
                case HAS_PREVIOUS_PAGE -> context.hasPreviousPage();
                case HAS_ITEMS -> context.usedSlots() > 0;
                case IS_EMPTY -> context.usedSlots() == 0;
                case IS_FULL -> context.usedSlots() >= context.capacity();
            };
            return met != negated;
        }
    }

    static @Nullable MenuIcon load(ConfigurationSection section, String name, Logger logger) {
        String rawMaterial = section.getString("material");
        Material material = rawMaterial == null ? null : Material.matchMaterial(rawMaterial);
        if (material == null || material.isAir()) {
            logger.warning("Menu item '" + name + "' has an unknown material '" + rawMaterial + "', skipping it.");
            return null;
        }

        String rawModel = section.getString("item-model");
        NamespacedKey itemModel = rawModel == null ? null : NamespacedKey.fromString(rawModel);
        if (rawModel != null && itemModel == null) {
            logger.warning("Menu item '" + name + "' has an invalid item-model '" + rawModel + "', ignoring it.");
        }

        return new MenuIcon(
                material,
                Math.clamp(section.getInt("amount", 1), 1, material.getMaxStackSize()),
                section.getString("name"),
                List.copyOf(section.getStringList("lore")),
                section.getBoolean("hide-tooltip", false),
                section.getBoolean("glow", false),
                itemModel,
                Action.parse(section.getString("action", "none"), logger),
                Condition.parse(section.getString("show-if", "always"), logger),
                null
        );
    }

    MenuIcon withFallback(@Nullable MenuIcon replacement) {
        return new MenuIcon(material, amount, name, lore, hideTooltip, glow, itemModel, action, condition, replacement);
    }

    /** The icon to show for the given state, or null when the slot stays empty. */
    public @Nullable MenuIcon visible(MenuContext context) {
        return condition.test(context) ? this : fallback;
    }

    /**
     * The extra resolver lets a menu add placeholders only it knows, such as an upgrade level,
     * and {@code viewer} is who any other plugin's placeholders are filled in for.
     */
    public ItemStack render(MenuContext context, Lang lang, @Nullable Player viewer, TagResolver extra) {
        ItemStack stack = new ItemStack(material, amount);
        stack.editMeta(meta -> {
            if (name != null) {
                meta.displayName(lang.parse(viewer, name, context.placeholders(), extra));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> lang.parse(viewer, line, context.placeholders(), extra))
                        .toList());
            }
            if (hideTooltip) {
                meta.setHideTooltip(true);
            }
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (itemModel != null) {
                meta.setItemModel(itemModel);
            }
        });
        return stack;
    }

    private static <T extends Enum<T>> @Nullable T read(String raw, Class<T> type) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
