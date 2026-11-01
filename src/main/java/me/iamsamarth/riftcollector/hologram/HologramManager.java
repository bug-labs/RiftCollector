package me.iamsamarth.riftcollector.hologram;

import me.iamsamarth.riftcollector.ability.Ability;
import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.collector.CollectorName;
import me.iamsamarth.riftcollector.collector.CollectorService;
import me.iamsamarth.riftcollector.config.PluginConfig;
import me.iamsamarth.riftcollector.hook.EconomyService;
import me.iamsamarth.riftcollector.lang.Lang;
import me.iamsamarth.riftcollector.upgrade.Upgrades;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Floating text above each collector, drawn with vanilla text displays so no hologram plugin is
 * needed. The displays are never saved to the world, so a crash cannot leave any behind, and the
 * refresh pass spawns whatever is missing in loaded chunks.
 */
public final class HologramManager {

    private final CollectorService collectors;
    private final Lang lang;
    private final EconomyService economy;
    private final Map<UUID, TextDisplay> displays = new HashMap<>();

    private Upgrades upgrades;
    private PluginConfig.Holograms settings;
    private PluginConfig.Names names;

    public HologramManager(CollectorService collectors, Lang lang, Upgrades upgrades, EconomyService economy,
                           PluginConfig.Holograms settings, PluginConfig.Names names) {
        this.collectors = collectors;
        this.lang = lang;
        this.upgrades = upgrades;
        this.economy = economy;
        this.settings = settings;
        this.names = names;
    }

    public void settings(PluginConfig.Holograms settings, PluginConfig.Names names, Upgrades upgrades) {
        this.settings = settings;
        this.names = names;
        this.upgrades = upgrades;
    }

    /** Spawns what is missing, updates what is there, and drops what should no longer show. */
    public void refreshAll() {
        for (Collector collector : List.copyOf(collectors.index().all())) {
            refresh(collector);
        }
    }

    public void refresh(Collector collector) {
        if (!settings.enabled() || !collector.enabled(Ability.HOLOGRAM)) {
            remove(collector);
            return;
        }
        World world = Bukkit.getWorld(collector.world());
        if (world == null || !world.isChunkLoaded(collector.x() >> 4, collector.z() >> 4)) {
            // An unloaded chunk takes its display with it, since nothing about it is saved.
            displays.remove(collector.id());
            return;
        }

        TextDisplay display = displays.get(collector.id());
        if (display == null || !display.isValid()) {
            display = spawn(world, collector);
            displays.put(collector.id(), display);
        } else {
            // Styles can change with a reload, so they are reapplied rather than set once.
            style(display);
        }
        display.text(text(collector));
    }

    public void remove(Collector collector) {
        TextDisplay display = displays.remove(collector.id());
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public void removeAll() {
        displays.values().stream().filter(display -> display != null && display.isValid())
                .forEach(TextDisplay::remove);
        displays.clear();
    }

    private TextDisplay spawn(World world, Collector collector) {
        Location location = new Location(world, collector.x() + 0.5,
                collector.y() + settings.offsetY(), collector.z() + 0.5);
        return world.spawn(location, TextDisplay.class, display -> {
            display.setPersistent(false);
            style(display);
            display.text(text(collector));
        });
    }

    /** Applies everything from the style section, so the display is as tweakable as a vanilla one. */
    private void style(TextDisplay display) {
        PluginConfig.HologramStyle style = settings.style();
        display.setShadowed(style.textShadow());
        display.setSeeThrough(style.seeThrough());
        display.setBillboard(readEnum(style.billboard(), Display.Billboard.class, Display.Billboard.CENTER));
        display.setAlignment(readEnum(style.alignment(), TextDisplay.TextAlignment.class,
                TextDisplay.TextAlignment.CENTER));
        display.setLineWidth(style.lineWidth());
        display.setViewRange((float) style.viewRange());

        if (style.textOpacity() >= 0) {
            display.setTextOpacity((byte) Math.clamp(style.textOpacity(), 1, 255));
        }
        display.setDefaultBackground(false);
        display.setBackgroundColor(style.background()
                ? backgroundColor(style)
                : Color.fromARGB(0, 0, 0, 0));
        display.setBrightness(style.brightness() < 0
                ? null
                : new Display.Brightness(Math.clamp(style.brightness(), 0, 15), Math.clamp(style.brightness(), 0, 15)));

        float scale = (float) style.scale();
        display.setTransformation(new Transformation(
                new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
    }

    private Color backgroundColor(PluginConfig.HologramStyle style) {
        String raw = style.backgroundColor().replace("#", "").trim();
        try {
            int rgb = Integer.parseInt(raw, 16);
            return Color.fromARGB(style.backgroundOpacity(),
                    (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException exception) {
            return Color.fromARGB(style.backgroundOpacity(), 0, 0, 0);
        }
    }

    private <T extends Enum<T>> T readEnum(String raw, Class<T> type, T fallback) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private Component text(Collector collector) {
        TagResolver placeholders = placeholders(collector);
        // A hologram hangs in the world rather than belonging to a viewer, so another plugin's
        // placeholders are filled in for the owner, whose collector it is.
        OfflinePlayer owner = collector.owner() == null ? null : Bukkit.getOfflinePlayer(collector.owner());
        Component text = Component.empty();
        List<String> lines = settings.lines();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                text = text.append(Component.newline());
            }
            text = text.append(lang.parse(owner, lines.get(index), placeholders));
        }
        return text;
    }

    private TagResolver placeholders(Collector collector) {
        @Nullable String owner = collector.ownerName();
        return TagResolver.resolver(
                Placeholder.component("name", CollectorName.of(collector, lang, names)),
                owner == null
                        ? Placeholder.component("owner", lang.get("collector.unknown-owner"))
                        : Placeholder.unparsed("owner", owner),
                Placeholder.unparsed("items", String.format(Locale.ROOT, "%,d", collector.itemCount())),
                Placeholder.unparsed("stored", Integer.toString(collector.usedSlots())),
                Placeholder.unparsed("capacity", Integer.toString(upgrades.slots(collector))),
                Placeholder.unparsed("earnings", economy.format(collector.earnings())),
                Placeholder.unparsed("sold", String.format(Locale.ROOT, "%,d", collector.itemsSold())));
    }
}
