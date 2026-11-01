package me.iamsamarth.riftcollector.lang;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every message the plugin shows, loaded from lang/&lt;locale&gt;.yml. Keys missing from a
 * translation fall back to the bundled English file, and {@code <lang:some.key>} inside any
 * message or menu file inserts another message, placeholders included.
 */
public final class Lang {

    private static final String DEFAULT_LOCALE = "en_US";
    private static final int MAX_DEPTH = 8;

    private static final char LEGACY_SECTION = '§';
    private static final Pattern LEGACY_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"), Map.entry('r', "<reset>"));

    private final Plugin plugin;
    private final Set<String> reportedMissing = new HashSet<>();
    private FileConfiguration messages = new YamlConfiguration();
    private Placeholders placeholders = Placeholders.NONE;

    public Lang(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Hands over another plugin's placeholders, which then work in every line loaded here. */
    public void placeholders(Placeholders placeholders) {
        this.placeholders = placeholders;
    }

    public void load(String locale) {
        reportedMissing.clear();
        File defaultFile = new File(plugin.getDataFolder(), "lang/" + DEFAULT_LOCALE + ".yml");
        if (!defaultFile.exists()) {
            plugin.saveResource("lang/" + DEFAULT_LOCALE + ".yml", false);
        }

        String resolved = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        File file = new File(plugin.getDataFolder(), "lang/" + resolved + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + resolved + ".yml not found, using "
                    + DEFAULT_LOCALE + " instead.");
            file = defaultFile;
        }

        FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        FileConfiguration bundled = bundledDefaults();
        if (bundled != null) {
            loaded.setDefaults(bundled);
        }
        messages = loaded;
    }

    public Component get(String key, TagResolver... placeholders) {
        return get(null, key, placeholders);
    }

    /** {@code target} is whose placeholders a line is filled in for; null uses none. */
    public Component get(@Nullable OfflinePlayer target, String key, TagResolver... placeholders) {
        return parse(target, raw(key), 0, placeholders);
    }

    public List<Component> getList(String key, TagResolver... placeholders) {
        return getList(null, key, placeholders);
    }

    public List<Component> getList(@Nullable OfflinePlayer target, String key, TagResolver... placeholders) {
        List<String> lines = messages.getStringList(key);
        List<Component> components = new ArrayList<>(lines.size());
        for (String line : lines) {
            components.add(parse(target, line, 0, placeholders));
        }
        return components;
    }

    /** A message to a player is filled in for that player, which is what they expect to read. */
    public void send(Audience audience, String key, TagResolver... placeholders) {
        audience.sendMessage(get(audience instanceof OfflinePlayer player ? player : null, key, placeholders));
    }

    /** Parses text that is not itself a message, such as a name from a menu file. */
    public Component parse(String raw, TagResolver... placeholders) {
        return parse(null, raw, 0, placeholders);
    }

    public Component parse(@Nullable OfflinePlayer target, String raw, TagResolver... placeholders) {
        return parse(target, raw, 0, placeholders);
    }

    private Component parse(@Nullable OfflinePlayer target, String raw, int depth,
                            TagResolver... placeholders) {
        TagResolver langTag = TagResolver.resolver("lang", (arguments, context) -> {
            String key = arguments.popOr("The lang tag needs a message key").value();
            Component inserted = depth >= MAX_DEPTH
                    ? Component.text(key)
                    : parse(target, raw(key), depth + 1, placeholders);
            return Tag.selfClosingInserting(inserted);
        });

        // Another plugin's placeholders are filled in first: what they return is text, which
        // this then colours and reads tags from like any other line.
        String filled = this.placeholders.apply(target, raw);
        return MiniMessage.miniMessage()
                .deserialize(modernize(filled), TagResolver.resolver(langTag, TagResolver.resolver(placeholders)))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * Rewrites the older colour codes as MiniMessage tags, so &amp;c, &amp;#ff0000 and
     * &lt;red&gt; can all be used in the same line of a config or language file.
     */
    static String modernize(String raw) {
        if (raw.indexOf('&') < 0 && raw.indexOf(LEGACY_SECTION) < 0) {
            return raw;
        }

        String hexed = LEGACY_HEX.matcher(raw).replaceAll(match -> "<#" + match.group(1).toLowerCase(Locale.ROOT) + ">");
        Matcher matcher = LEGACY_CODE.matcher(hexed);
        StringBuilder result = new StringBuilder(hexed.length());
        while (matcher.find()) {
            String tag = LEGACY_TAGS.get(Character.toLowerCase(matcher.group(1).charAt(0)));
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(tag == null ? matcher.group() : tag));
        }
        return matcher.appendTail(result).toString();
    }

    private String raw(String key) {
        String value = messages.getString(key);
        if (value != null) {
            return value;
        }
        if (reportedMissing.add(key)) {
            plugin.getLogger().warning("Missing language key '" + key + "'.");
        }
        return key;
    }

    private @Nullable FileConfiguration bundledDefaults() {
        InputStream resource = plugin.getResource("lang/" + DEFAULT_LOCALE + ".yml");
        if (resource == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled language file: " + exception.getMessage());
            return null;
        }
    }
}
