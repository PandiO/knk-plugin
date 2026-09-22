package net.knightsandkings.knk.paper.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-house free-text capture via a virtual anvil GUI (IMPLEMENTATION_PLAN.md
 * Phase 7, DESIGN_REVIEW.md §2.1 (updated)/§2.5, QOL_BUGFIX_BACKLOG.md item
 * 8): opens an {@link AnvilInventory}, lets the player type into its rename
 * field the normal Minecraft way, and hands the typed text back on an
 * explicit Confirm click - the exact technique the (rejected-as-a-dependency)
 * AnvilGUI library is built on, built directly against Bukkit's own API
 * instead. No third-party jar.
 * <p>
 * Post-Phase-8 QOL follow-up: the first version of this class relied on
 * vanilla anvil-combine semantics (an empty second slot, output computed by
 * Bukkit from slot 0 alone) and had no visible Cancel affordance - closing
 * the anvil (Escape) was the only way to cancel, which testing found
 * unclear. This version places a real Cancel item in slot 1 and forces the
 * result slot's contents unconditionally via {@link #onPrepareAnvil}
 * (regardless of what combining slots 0+1 would normally produce - the
 * Cancel item in slot 1 would otherwise feed into vanilla's repair/combine
 * logic and corrupt the output), reading the actual typed text via {@link
 * AnvilInventory#getRenameText()} rather than the result item's own display
 * name (which is now a fixed "Confirm" label, not the raw text).
 * <p>
 * Deliberately mirrors {@code ChatCaptureManager.startTextCapture}'s
 * {@code (player, prompt, onComplete, onCancel)} signature so
 * {@code MenuService}'s two call sites (search/filter prompts) could swap
 * from one to the other with no shape change - see that method's own javadoc
 * for why the swap happened. Scope is deliberately narrow: this replaces
 * {@code ChatCaptureManager}'s TEXT_INPUT flow for InventoryMenu's search/
 * filter capture only; {@code ChatCaptureManager} itself (its account-merge
 * flow, its generic TEXT_INPUT mechanism) is untouched and still exists for
 * any other caller.
 * <p>
 * Opening this Inventory closes whatever the player had open before it (the
 * same "opening anything else closes the current custom Inventory" vanilla
 * behavior {@code MenuService.changePage}/{@code applyContentQueryUpdate}
 * already work around) - {@code MenuLifecycleListener}'s existing
 * {@code InventoryCloseEvent} handler already only reacts when the closing
 * Inventory equals the tracked {@code OpenMenuContext}, so it's a no-op for
 * this capture's own anvil Inventory; the menu re-fetch/reopen on completion
 * is handled entirely by the {@code onComplete} callback the caller supplies
 * (see {@code MenuService#search}/{@code #filter}), not by this class.
 */
public final class AnvilCaptureManager implements Listener {

    private static final int INPUT_SLOT = 0;
    private static final int CANCEL_SLOT = 1;
    private static final int CONFIRM_SLOT = 2;

    private final Plugin plugin;
    private final Map<UUID, AnvilCaptureSession> activeSessions = new ConcurrentHashMap<>();

    public AnvilCaptureManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param promptMessage sent to the player as a chat line before the anvil opens
     *                       (the anvil GUI itself has no room for arbitrary prompt text)
     * @param onComplete     receives the typed text, stripped of color codes; blank
     *                       is passed through as "" rather than treated specially -
     *                       callers that mean "clear" on blank (search/filter) already
     *                       handle that themselves
     * @param onCancel       invoked if the player closes the anvil without confirming
     *                       (Escape, or clicking the Cancel item, or closing after
     *                       either)
     */
    public void startTextCapture(Player player, String promptMessage, Consumer<String> onComplete, Runnable onCancel) {
        player.sendMessage(promptMessage);

        Inventory anvil = Bukkit.createInventory(null, InventoryType.ANVIL, "Enter text");
        anvil.setItem(INPUT_SLOT, namedItem(Material.PAPER, ChatColor.WHITE + "Type here"));
        anvil.setItem(CANCEL_SLOT, namedItem(Material.BARRIER, ChatColor.RED + "Cancel"));

        activeSessions.put(player.getUniqueId(), new AnvilCaptureSession(anvil, onComplete, onCancel));
        player.openInventory(anvil);
    }

    /**
     * Forces the result slot unconditionally to a plain "Confirm" affordance
     * showing the currently-typed text, and zeroes the repair cost - both
     * regardless of whatever vanilla's real anvil-combine logic would have
     * computed from slots 0+1 (the Cancel item sitting in slot 1 would
     * otherwise be fed into that combine as a second ingredient, which could
     * null out the result entirely or apply real repair/enchant-merge
     * semantics neither slot is meant to trigger).
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!isTrackedAnvil(event.getInventory())) {
            return;
        }
        AnvilInventory anvilInventory = event.getInventory();
        anvilInventory.setRepairCost(0);

        String typed = anvilInventory.getRenameText();
        ItemStack confirmItem = namedItem(Material.PAPER, ChatColor.GREEN + "Confirm");
        ItemMeta meta = confirmItem.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(ChatColor.GRAY + "You typed: " + ChatColor.WHITE
                    + (typed != null && !typed.isBlank() ? typed : ChatColor.DARK_GRAY + "(nothing)" + ChatColor.GRAY)));
            confirmItem.setItemMeta(meta);
        }
        event.setResult(confirmItem);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        AnvilCaptureSession session = activeSessions.get(player.getUniqueId());
        if (session == null || !session.inventory().equals(event.getInventory())) {
            return;
        }

        // Never allow taking/moving items in a capture anvil - only clicks
        // directly on the Confirm (result) or Cancel slots do anything.
        event.setCancelled(true);

        boolean clickedInTopInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(session.inventory());
        if (!clickedInTopInventory) {
            return;
        }

        if (event.getSlot() == CONFIRM_SLOT) {
            String text = extractText(session.inventory());
            complete(player, session, text);
        } else if (event.getSlot() == CANCEL_SLOT) {
            cancel(player, session);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        AnvilCaptureSession session = activeSessions.get(player.getUniqueId());
        if (session == null || !session.inventory().equals(event.getInventory())) {
            return;
        }

        // A successful Confirm/Cancel click already removed the session
        // (see #complete/#cancel) before closing the inventory itself, so
        // reaching here means the player closed it some other way (Escape,
        // etc.) - a genuine cancel, not a double-completion.
        activeSessions.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, session.onCancel());
    }

    /**
     * Deferred a tick: closing an Inventory from inside its own click handler
     * is a common source of reentrancy trouble in Bukkit/Paper, so both the
     * close and the completion callback happen next tick.
     */
    private void complete(Player player, AnvilCaptureSession session, String text) {
        activeSessions.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            session.onComplete().accept(text);
        });
    }

    private void cancel(Player player, AnvilCaptureSession session) {
        activeSessions.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            session.onCancel().run();
        });
    }

    private boolean isTrackedAnvil(Inventory inventory) {
        return activeSessions.values().stream().anyMatch(session -> session.inventory().equals(inventory));
    }

    /** Reads the live typed text straight from the anvil's rename field - not the result item's own (now-fixed) display name. */
    private static String extractText(Inventory inventory) {
        if (!(inventory instanceof AnvilInventory anvilInventory)) {
            return "";
        }
        String renameText = anvilInventory.getRenameText();
        return renameText != null ? ChatColor.stripColor(renameText) : "";
    }

    private static ItemStack namedItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            item.setItemMeta(meta);
        }
        return item;
    }

    private record AnvilCaptureSession(Inventory inventory, Consumer<String> onComplete, Runnable onCancel) {
    }
}
