package me.iamsamarth.riftcollector.lang;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Fills in another plugin's placeholders before a line is parsed, so %papi_style% text works
 * anywhere this plugin takes text. Without such a plugin the line is handed back untouched.
 */
public interface Placeholders {

    Placeholders NONE = (target, raw) -> raw;

    /** {@code target} is who the line is about, which may be an offline collector owner. */
    String apply(@Nullable OfflinePlayer target, String raw);
}
