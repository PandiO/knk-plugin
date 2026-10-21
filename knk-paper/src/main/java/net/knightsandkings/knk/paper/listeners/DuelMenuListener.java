package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.duel.ui.DuelKitPicker;
import net.knightsandkings.knk.paper.duel.ui.DuelNegotiationUi;
import net.knightsandkings.knk.paper.duel.ui.DuelOfferView;
import net.knightsandkings.knk.paper.duel.ui.DuelProposalEditor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * The duel menus (arena duels DESIGN §6.4): every click and drag while the proposal editor, the offer view or the
 * kit picker is open is cancelled (top and bottom inventory, any click type), so no item ever moves; the click is
 * then handed to the menu. A click on the player's own inventory in the editor adds a stake reference (D3).
 */
public final class DuelMenuListener implements Listener {

    private final DuelNegotiationUi ui;

    public DuelMenuListener(DuelNegotiationUi ui) {
        this.ui = ui;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!isDuelMenu(holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) return;
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        switch (holder) {
            case DuelProposalEditor.Holder editor ->
                    ui.editor().click(player, editor, top, event.getSlot(), top ? null : event.getCurrentItem());
            case DuelOfferView.Holder view -> {
                if (top) ui.offerView().click(player, view, event.getSlot());
            }
            case DuelKitPicker.Holder picker -> {
                if (top) ui.kitPicker().click(player, picker, event.getSlot());
            }
            default -> { }
        }
    }

    /** Another plugin can't un-cancel a click in a duel menu. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void keepCancelled(InventoryClickEvent event) {
        if (isDuelMenu(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (isDuelMenu(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof DuelProposalEditor.Holder editor) ui.editorClosed(player, editor);
        else if (holder instanceof DuelKitPicker.Holder picker) ui.kitPickerClosed(player, picker);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ui.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ui.onQuit(event.getPlayer());
    }

    static boolean isDuelMenu(InventoryHolder holder) {
        return holder instanceof DuelProposalEditor.Holder || holder instanceof DuelOfferView.Holder
                || holder instanceof DuelKitPicker.Holder;
    }
}
