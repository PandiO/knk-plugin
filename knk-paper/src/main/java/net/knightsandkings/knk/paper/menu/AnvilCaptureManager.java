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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-house free-text capture via a virtual anvil GUI (IMPLEMENTATION_PLAN.md
 * Phase 7, DESIGN_REVIEW.md §2.1 (updated)/§2.5, QOL_BUGFIX_BACKLOG.md item
 * 8): opens an {@link AnvilInventory}, lets the player type into its rename
 * field the normal Minecraft way, reads the renamed text off a click on the
 * output slot, closes the anvil, and hands the text back - the exact
 * technique the (rejected-as-a-dependency) AnvilGUI library is built on,
 * built directly against Bukkit's own API instead. No third-party jar.
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

    private final Plugin plugin;
    private final Map<UUID, AnvilCaptureSession> activeSessions = new ConcurrentHashMap<>();

    public AnvilCaptureManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param promptMessage sent to the player as a chat line before the anvil opens
     *                       (the anvil GUI itself has no room for arbitrary prompt text)
     * @param onComplete     receives the renamed text, stripped of color codes; blank
     *                       (the item's unrenamed default name) is passed through as ""
     *                       rather than treated specially - callers that mean "clear" on
     *                       blank (search/filter) already handle that themselves
     * @param onCancel       invoked if the player closes the anvil without confirming a
     *                       rename (Escape, or clicking anywhere but the output slot then
     *                       closing)
     */
    public void startTextCapture(Player player, String promptMessage, Consumer<String> onComplete, Runnable onCancel) {
        player.sendMessage(promptMessage);

        Inventory anvil = Bukkit.createInventory(null, InventoryType.ANVIL, "Enter text");
        ItemStack input = new ItemStack(Material.PAPER);
        anvil.setItem(0, input);

        activeSessions.put(player.getUniqueId(), new AnvilCaptureSession(anvil, onComplete, onCancel));
        player.openInventory(anvil);
    }

    /** Zeroes the repair cost on every prepare so a plain rename never costs XP or hits the "Too Expensive!" cap. */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!isTrackedAnvil(event.getInventory())) {
            return;
        }
        if (event.getInventory() instanceof AnvilInventory anvilInventory) {
            anvilInventory.setRepairCost(0);
        }
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

        // Never allow taking/moving items in a capture anvil - only a click
        // directly on the output slot (handled below) completes the capture.
        event.setCancelled(true);

        boolean clickedOutput = event.getSlotType() == InventoryType.SlotType.RESULT
                && event.getClickedInventory() != null
                && event.getClickedInventory().equals(session.inventory());
        if (!clickedOutput) {
            return;
        }

        String text = extractText(event.getCurrentItem());
        activeSessions.remove(player.getUniqueId());
        // Deferred a tick: closing an Inventory from inside its own click
        // handler is a common source of reentrancy trouble in Bukkit/Paper,
        // so both the close and the completion callback happen next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            session.onComplete().accept(text);
        });
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

        // A successful output-slot click already removed the session (see
        // #onClick) before closing the inventory itself, so reaching here
        // means the player closed it some other way (Escape, etc.) without
        // confirming a capture - a genuine cancel, not a double-completion.
        activeSessions.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, session.onCancel());
    }

    private boolean isTrackedAnvil(Inventory inventory) {
        return activeSessions.values().stream().anyMatch(session -> session.inventory().equals(inventory));
    }

    private static String extractText(ItemStack result) {
        if (result == null) {
            return "";
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return "";
        }
        return ChatColor.stripColor(meta.getDisplayName());
    }

    private record AnvilCaptureSession(Inventory inventory, Consumer<String> onComplete, Runnable onCancel) {
    }
}
