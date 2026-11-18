package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clicks with the navigation compass (KNG-234): a right-click in the air (the sky included) or on a block that does nothing
 * on its own cycles the destination, a shift-click switches navigation off and on. A click on a door,
 * chest, button or the like is left to that block (unless the player sneaks, which skips the block anyway).
 */
public final class NavigationCompassListener implements Listener {

    private final NavigationCompassService compass;

    public NavigationCompassListener(NavigationCompassService compass) {
        this.compass = Objects.requireNonNull(compass, "compass");
    }

    /**
     * Not {@code ignoreCancelled}: Bukkit reports a click in the air as <i>cancelled</i> (there is no block to interact
     * with: {@code isCancelled()} is {@code useInteractedBlock() == DENY}), so ignoring cancelled events silently drops
     * every right-click on the sky - the compass would only work against a block. What matters is whether somebody
     * denied the use of the <b>item</b> ({@code useItemInHand()}), which is what a protection plugin or the gate
     * listener sets when it cancels a click on purpose.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!compass.isCompass(event.getItem())) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking() && interactsOnItsOwn(event.getClickedBlock())) {
            return;
        }
        if (compass.use(player, player.isSneaking())) {
            // an item that does something when used (an ender pearl, a bow) must not do it as well
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        compass.forget(event.getPlayer().getUniqueId());
    }

    private static boolean interactsOnItsOwn(Block block) {
        return block != null && block.getType().isInteractable();
    }
}
