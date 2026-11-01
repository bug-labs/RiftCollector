package me.iamsamarth.riftcollector.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import me.iamsamarth.riftcollector.collector.Collector;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The people the owner let into this collector. Names are typed in chat rather than picked from
 * a list, so players who have never been on the server can be added too.
 */
public final class TrustMenu extends Menu {

    private final List<Integer> listSlots = new ArrayList<>();
    private @Nullable String entryIcon;
    private int page = 1;

    TrustMenu(Menus menus, MenuLayout layout, Collector collector) {
        super(menus, layout, collector);
        ConfigurationSection section = layout.section("list");
        if (section != null) {
            entryIcon = section.getString("icon");
        }
        for (int slot : layout.contentSlots()) {
            listSlots.add(slot);
        }
    }

    @Override
    protected void onMenuClick(InventoryClickEvent event, Player player) {
        int contentIndex = layout.contentIndexOf(event.getSlot());
        if (contentIndex < 0) {
            super.onMenuClick(event, player);
            return;
        }
        if (!canManage(player)) {
            lang.send(player, "trust.owner-only");
            return;
        }

        List<Map.Entry<UUID, String>> entries = entries();
        int index = (page - 1) * pageSize() + contentIndex;
        if (index >= entries.size()) {
            return;
        }
        Map.Entry<UUID, String> removed = entries.get(index);
        collector.untrust(removed.getKey());
        lang.send(player, "trust.removed", Placeholder.unparsed("player", name(removed)));
        registry.refresh(collector);
    }

    @Override
    protected void onAction(MenuIcon.Action action, Player player) {
        switch (action) {
            case TRUST_ADD -> askForName(player);
            case NEXT_PAGE -> turnTo(page + 1);
            case PREVIOUS_PAGE -> turnTo(page - 1);
            default -> super.onAction(action, player);
        }
    }

    private void askForName(Player player) {
        if (!canManage(player)) {
            lang.send(player, "trust.owner-only");
            return;
        }
        player.closeInventory();
        lang.send(player, "trust.prompt");
        services.prompts().request(player, name -> {
            if (name == null || name.isBlank()) {
                lang.send(player, "trust.cancelled");
                menus.openTrust(player, collector);
                return;
            }
            resolve(player, name);
        });
    }

    /**
     * Looks the name up in the server's own player cache first and only asks Mojang when it has
     * never seen the name, which keeps the common case off the network entirely.
     */
    private void resolve(Player player, String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            add(player, cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
            return;
        }

        lang.send(player, "trust.looking-up", Placeholder.unparsed("player", name));
        Bukkit.getScheduler().runTaskAsynchronously(services.plugin(), () -> {
            PlayerProfile profile = Bukkit.createProfile(name);
            boolean complete = profile.completeFromCache() || profile.complete(false);
            UUID id = profile.getId();
            String resolved = profile.getName() == null ? name : profile.getName();
            Bukkit.getScheduler().runTask(services.plugin(), () -> {
                if (!complete || id == null) {
                    lang.send(player, "trust.unknown-player", Placeholder.unparsed("player", name));
                    menus.openTrust(player, collector);
                    return;
                }
                add(player, id, resolved);
            });
        });
    }

    private void add(Player player, UUID id, String name) {
        if (id.equals(collector.owner())) {
            lang.send(player, "trust.already-owner");
        } else if (collector.isTrusted(id)) {
            lang.send(player, "trust.already-trusted", Placeholder.unparsed("player", name));
        } else {
            collector.trust(id, name);
            lang.send(player, "trust.added", Placeholder.unparsed("player", name));
        }
        menus.openTrust(player, collector);
    }

    @Override
    protected void renderContents() {
        MenuContext context = context();
        List<Map.Entry<UUID, String>> entries = entries();
        for (int index = 0; index < listSlots.size(); index++) {
            int entryIndex = (page - 1) * pageSize() + index;
            if (entryIndex >= entries.size()) {
                inventory.setItem(listSlots.get(index), null);
                continue;
            }
            inventory.setItem(listSlots.get(index), head(entries.get(entryIndex), context));
        }
    }

    private ItemStack head(Map.Entry<UUID, String> entry, MenuContext context) {
        MenuIcon icon = layout.icon(entryIcon);
        ItemStack stack = icon == null
                ? new ItemStack(org.bukkit.Material.PLAYER_HEAD)
                : icon.render(context, lang, viewer, TagResolver.resolver(
                        Placeholder.unparsed("player", name(entry)), placeholders()));
        if (stack.getItemMeta() instanceof SkullMeta) {
            stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getKey())));
        }
        return stack;
    }

    @Override
    protected void reopen(Player player) {
        menus.openTrust(player, collector);
    }

    @Override
    protected MenuContext context() {
        int size = collector.trusted().size();
        return new MenuContext(page, Math.max(1, (int) Math.ceil((double) size / pageSize())),
                size, size, size);
    }

    private void turnTo(int target) {
        if (target < 1 || target > context().totalPages() || target == page) {
            return;
        }
        page = target;
        render();
    }

    private int pageSize() {
        return Math.max(1, listSlots.size());
    }

    private List<Map.Entry<UUID, String>> entries() {
        return new ArrayList<>(collector.trusted().entrySet());
    }

    private String name(Map.Entry<UUID, String> entry) {
        return entry.getValue() == null ? entry.getKey().toString() : entry.getValue();
    }
}
