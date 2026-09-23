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
 * Post-Phase-8 QOL follow-up, second iteration: the first attempt at an
 * explicit Cancel affordance placed a real Barrier item in slot 1 (the
 * anvil's second/"sacrifice" input). Live testing found this broke the
 * capture entirely - no Confirm item ever appeared, and confirming produced
 * no text. Root cause: vanilla's anvil only treats a plain rename (no
 * second ingredient) as its own always-valid mini-operation when slot 1 is
 * genuinely empty; the moment slot 1 holds something, vanilla instead tries
 * to validate a repair/combine using slots 0+1 together, and
 * Paper+Barrier isn't a valid combination - the whole operation (including
 * the name change) is treated as invalid, which can leave {@link
 * AnvilInventory#getRenameText()} empty and the client unwilling to render
 * a result item at all, regardless of what {@link #onPrepareAnvil} forces
 * into the result slot via {@code setResult}. Slot 1 must stay empty for
 * the rename to work reliably at all.
 * <p>
 * This version instead repurposes slot 0 (the input item itself) as Cancel:
 * clicking it was already observed to "do nothing but reset the display" -
 * since every click here is cancelled (never lets the item actually leave
 * the slot), that reset was always just Bukkit re-syncing the inventory
 * back to server state, not a bug in the rename mechanism itself. Made
 * intentional: clicking slot 0 now explicitly triggers {@code onCancel}.
 * Slot 1 stays empty. Slot 2 (the real result slot) still gets its result
 * forced unconditionally via {@link #onPrepareAnvil} to a clear "Confirm"
 * label (rather than left as vanilla's own renamed-item preview), reading
 * the actual typed text via {@link AnvilInventory#getRenameText()} rather
 * than the result item's own display name.
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

    /** Doubles as the rename target and (since clicking it can never remove it - every click here is cancelled) the Cancel affordance. */
    private static final int INPUT_SLOT = 0;
    /** Must stay empty - see the class javadoc for why a real item here breaks the rename computation. */
    private static final int SECOND_INGREDIENT_SLOT = 1;
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
     *                       (Escape, or clicking the input/Cancel item, or closing
     *                       after either)
     */
    public void startTextCapture(Player player, String promptMessage, Consumer<String> onComplete, Runnable onCancel) {
        player.sendMessage(promptMessage);

        Inventory anvil = Bukkit.createInventory(null, InventoryType.ANVIL, "Enter text");
        ItemStack input = namedItem(Material.PAPER, ChatColor.WHITE + "Type here");
        ItemMeta inputMeta = input.getItemMeta();
        if (inputMeta != null) {
            inputMeta.setLore(List.of(ChatColor.RED + "Click to cancel"));
            input.setItemMeta(inputMeta);
        }
        anvil.setItem(INPUT_SLOT, input);
        // SECOND_INGREDIENT_SLOT deliberately left empty.

        activeSessions.put(player.getUniqueId(), new AnvilCaptureSession(anvil, onComplete, onCancel));
        player.openInventory(anvil);
    }

    /**
     * Forces the result slot unconditionally to a plain "Confirm" affordance
     * showing the currently-typed text, and zeroes the repair cost. With
     * {@code SECOND_INGREDIENT_SLOT} empty, vanilla's own rename-only
     * computation is already valid on its own (this is what makes {@code
     * getRenameText()} reliable at all) - this override only swaps the
     * cosmetic result item for a clearer "Confirm" label, it doesn't need to
     * rescue an otherwise-broken computation the way it would if slot 1 held
     * something.
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
        } else if (event.getSlot() == INPUT_SLOT) {
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
