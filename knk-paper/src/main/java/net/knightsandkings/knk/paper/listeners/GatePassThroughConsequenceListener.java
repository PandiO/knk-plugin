package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import net.knightsandkings.knk.paper.gates.GatePassThroughService;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Reacts to GateDoorInteractEvent (a right-click on a closed, active gate door - see
 * GateDoorHitService) by resolving whether the player is authorized for gate pass-through and,
 * if so, which of the three modes to use, then dispatching to GatePassThroughService.
 *
 * Mirrors the detection/consequence split GateDamageConsequenceListener established for damage:
 * GateDoorHitService/GateEventListener only detect and fire the event, this class owns the
 * permission check and gameplay consequence.
 */
public class GatePassThroughConsequenceListener implements Listener {
    private final GatePassThroughService passThroughService;
    private final UserManager userManager;

    public GatePassThroughConsequenceListener(GatePassThroughService passThroughService, UserManager userManager) {
        this.passThroughService = passThroughService;
        this.userManager = userManager;
    }

    @EventHandler
    public void onGateDoorInteract(GateDoorInteractEvent event) {
        if (event.isCancelled()) {
            return;
        }

        CachedGateDoor gate = event.getGate();
        Player player = event.getPlayer();
        boolean isAdmin = player.hasPermission("knk.gate.admin");

        if (!gate.isEffectivelyAllowPassThrough() && !isAdmin) {
            return;
        }
        if (!isAdmin && !player.hasPermission("knk.gate.passthrough.use")) {
            return;
        }

        GatePassThroughMethod mode = resolveMode(player, isAdmin);
        passThroughService.dispatch(gate, player, mode);
    }

    /**
     * Resolves the player's preferred method, downgrading INSTANT_OPEN to DEFAULT when they lack
     * the extra knk.gate.passthrough.instant permission (silent fallback, not a denial - the gate
     * should "just work" at whatever tier the player is permitted).
     */
    private GatePassThroughMethod resolveMode(Player player, boolean isAdmin) {
        PlayerUserData userData = userManager.getCachedUser(player.getUniqueId());
        GatePassThroughMethod preferred = userData != null
            ? userData.gatePassThroughMethodDefault()
            : GatePassThroughMethod.DEFAULT;

        if (preferred == GatePassThroughMethod.INSTANT_OPEN
            && !isAdmin
            && !player.hasPermission("knk.gate.passthrough.instant")) {
            return GatePassThroughMethod.DEFAULT;
        }

        return preferred;
    }
}
