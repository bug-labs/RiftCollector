package me.iamsamarth.riftcollector.gui;

import me.iamsamarth.riftcollector.ability.SellService;
import me.iamsamarth.riftcollector.collector.Collector;
import me.iamsamarth.riftcollector.collector.CollectorName;
import me.iamsamarth.riftcollector.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shared behaviour of every collector menu: nothing in the menu is a real inventory slot, so all
 * clicks in it are cancelled and turned into actions.
 */
public abstract class Menu implements InventoryHolder {

    protected static final String BYPASS_PERMISSION = "riftcollector.bypass";
    protected static final String SELL_PERMISSION = "riftcollector.sell";
    protected static final String RENAME_PERMISSION = "riftcollector.rename";
    /** Typed instead of a name to go back to the plain "Collector". */
    private static final String RESET_NAME = "reset";

    protected final Menus menus;
    protected final MenuServices services;
    protected final MenuRegistry registry;
    protected final MenuLayout layout;
    protected final Lang lang;
    protected final Collector collector;

    protected Inventory inventory;
    protected @Nullable Player viewer;

    protected Menu(Menus menus, MenuLayout layout, Collector collector) {
        this.menus = menus;
        this.services = menus.services();
        this.registry = menus.registry();
        this.layout = layout;
        this.lang = layout.lang();
        this.collector = collector;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Collector collector() {
        return collector;
    }

    public void open(Player player) {
        viewer = player;
        build();
        registry.opened(this);
        player.openInventory(inventory);
    }

    public void refresh() {
        render();
    }

    public void close() {
        if (viewer != null) {
            viewer.closeInventory();
        }
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Double clicking gathers matching items from both inventories, the menu included.
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        Inventory clicked = event.getClickedInventory();
        if (clicked != null && clicked.equals(inventory)) {
            event.setCancelled(true);
            onMenuClick(event, player);
        } else if (clicked != null) {
            onPlayerInventoryClick(event, player);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        // Changing page closes the previous inventory; that one is already deregistered.
        if (event.getInventory().equals(inventory)) {
            registry.closed(this);
        }
    }

    protected void onMenuClick(InventoryClickEvent event, Player player) {
        MenuIcon icon = layout.iconAt(event.getSlot(), context());
        if (icon != null) {
            onAction(icon.action(), player);
        }
    }

    protected void onPlayerInventoryClick(InventoryClickEvent event, Player player) {
    }

    protected void onAction(MenuIcon.Action action, Player player) {
        switch (action) {
            case CLOSE -> player.closeInventory();
            case OPEN_UPGRADES -> menus.openUpgrades(player, collector);
            case OPEN_SETTINGS -> menus.openSettings(player, collector);
            case OPEN_FILTER -> menus.openFilter(player, collector);
            case OPEN_TRUST -> openTrust(player);
            case BACK -> menus.openCollector(player, collector);
            case SELL_ALL -> sellAll(player);
            case PICKUP -> pickup(player);
            case RENAME -> rename(player);
            default -> {
            }
        }
    }

    /** The owner, anyone they trusted, and staff with the bypass permission. */
    protected boolean canUse(Player player) {
        return collector.isOwner(player) || collector.isTrusted(player.getUniqueId())
                || player.hasPermission(BYPASS_PERMISSION);
    }

    /** Only the owner and staff: the trust list itself and picking the collector up. */
    protected boolean canManage(Player player) {
        return collector.isOwner(player) || player.hasPermission(BYPASS_PERMISSION);
    }

    private void openTrust(Player player) {
        if (!canManage(player)) {
            lang.send(player, "trust.owner-only");
            return;
        }
        menus.openTrust(player, collector);
    }

    private void sellAll(Player player) {
        if (!player.hasPermission(SELL_PERMISSION)) {
            lang.send(player, "sell.no-permission");
            return;
        }
        SellService.Sale sale = services.sales().sellAll(collector);
        if (sale.items() <= 0) {
            lang.send(player, "sell.nothing");
            return;
        }
        lang.send(player, "sell.sold",
                Placeholder.unparsed("money", services.economy().format(sale.money())),
                Placeholder.unparsed("items", String.format(Locale.ROOT, "%,d", sale.items())));
        registry.refresh(collector);
        services.holograms().refresh(collector);
    }

    /**
     * Renaming goes through chat: a name is free text, and an anvil would mean holding the
     * collector, which is exactly what a player with a full collector cannot do.
     */
    private void rename(Player player) {
        if (!canUse(player)) {
            lang.send(player, "ability.no-permission");
            return;
        }
        if (!player.hasPermission(RENAME_PERMISSION)) {
            lang.send(player, "rename.no-permission");
            return;
        }

        int maxLength = services.names().maxLength();
        player.closeInventory();
        lang.send(player, "rename.prompt", Placeholder.unparsed("max", Integer.toString(maxLength)));
        services.prompts().request(player, answer -> {
            if (answer == null) {
                lang.send(player, "rename.cancelled");
            } else if (answer.equalsIgnoreCase(RESET_NAME)) {
                collector.displayName(null);
                lang.send(player, "rename.reset");
            } else if (answer.length() > maxLength) {
                lang.send(player, "rename.too-long", Placeholder.unparsed("max", Integer.toString(maxLength)));
            } else {
                collector.displayName(answer);
                lang.send(player, "rename.renamed", Placeholder.unparsed("name", answer));
            }
            registry.refresh(collector);
            services.holograms().refresh(collector);
            reopen(player);
        });
    }

    private void pickup(Player player) {
        if (!canManage(player)) {
            lang.send(player, "collector.not-owner", Placeholder.unparsed("owner",
                    collector.ownerName() == null ? "-" : collector.ownerName()));
            return;
        }
        if (collector.itemCount() > 0) {
            lang.send(player, "collector.not-empty");
            return;
        }
        World world = Bukkit.getWorld(collector.world());
        if (world == null) {
            return;
        }

        player.closeInventory();
        Block block = world.getBlockAt(collector.x(), collector.y(), collector.z());
        ItemStack item = services.collectorItem().create(collector);
        services.collectors().remove(collector);
        if (block.getType() == collector.material()) {
            block.setType(Material.AIR);
        }

        player.getInventory().addItem(item).values()
                .forEach(rest -> world.dropItemNaturally(player.getLocation(), rest));
        lang.send(player, "collector.picked-up");
    }

    /** Where a menu sends the player back after something took them out of it. */
    protected void reopen(Player player) {
        menus.openCollector(player, collector);
    }

    protected void build() {
        inventory = layout.create(this, context(), viewer, placeholders());
        render();
    }

    protected void render() {
        layout.decorate(inventory, context(), viewer, placeholders());
        renderContents();
    }

    /** Anything the menu draws beyond the icons of its layout. */
    protected void renderContents() {
    }

    /**
     * Placeholders every icon of every menu can use. A menu adds its own on top rather than
     * replacing these, so a line like "Trusted: &lt;trusted&gt;" works wherever it is put.
     */
    protected TagResolver placeholders() {
        return TagResolver.resolver(
                Placeholder.component("name", CollectorName.of(collector, lang, services.names())),
                Placeholder.component("owner", collector.ownerName() == null
                        ? lang.get("collector.unknown-owner")
                        : Component.text(collector.ownerName())),
                Placeholder.unparsed("trusted", Integer.toString(collector.trusted().size())),
                Placeholder.unparsed("earnings", services.economy().format(collector.earnings())),
                Placeholder.unparsed("sold", String.format(Locale.ROOT, "%,d", collector.itemsSold())),
                Placeholder.component("rename-reason", renameReason()),
                lazy("value", () -> services.economy().format(services.sales().value(collector))));
    }

    /** Says on the rename button why it cannot be used, rather than leaving it a mystery. */
    private Component renameReason() {
        if (viewer == null || !canUse(viewer)) {
            return lang.get("ability.reason.not-trusted");
        }
        return viewer.hasPermission(RENAME_PERMISSION)
                ? lang.get("rename.reason.none")
                : lang.get("rename.reason.no-permission");
    }

    /**
     * A placeholder that is only worked out if some line actually uses it. Asking a shop plugin
     * what a collector is worth is far too expensive to do on every redraw.
     */
    protected static TagResolver lazy(String name, Supplier<String> value) {
        return TagResolver.resolver(name, (arguments, context) -> Tag.inserting(Component.text(value.get())));
    }

    protected abstract MenuContext context();
}
