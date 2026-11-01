package me.iamsamarth.riftcollector.collector;

import me.iamsamarth.riftcollector.config.PluginConfig;
import me.iamsamarth.riftcollector.lang.Lang;
import net.kyori.adventure.text.Component;

/**
 * How a collector's name is shown, in menus and above the block alike. A name is typed by a
 * player, so it is plain text unless the server has turned formatting on: tags in a name would
 * otherwise be parsed for everyone who opens the collector.
 */
public final class CollectorName {

    private CollectorName() {
    }

    public static Component of(Collector collector, Lang lang, PluginConfig.Names settings) {
        String name = collector.displayName();
        if (name == null) {
            return lang.get("collector.default-name");
        }
        return settings.allowFormatting() ? lang.parse(name) : Component.text(name);
    }
}
