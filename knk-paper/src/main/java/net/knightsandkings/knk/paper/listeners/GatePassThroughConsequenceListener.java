package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GatePassThroughStyles;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import net.knightsandkings.knk.paper.gates.GatePassThroughRules;
import net.knightsandkings.knk.paper.gates.GatePassThroughService;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Reacts to GateDoorInteractEvent (a right-click on a closed, active gate door - see
 * GateDoorHitService) by resolving whether the player is authorized for gate pass-through and,
 * if so, which pass-through style to use, then dispatching to GatePassThroughService.
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
        boolean isAdmin = GatePassThroughRules.isAdmin(player);

        // R25: the same predicate navigation uses to decide whether a gate edge is open for a player.
        if (!GatePassThroughRules.canPass(player, gate)) {
            return;
        }

        GatePassThroughMethod mode = resolveMode(player, gate, isAdmin);
        passThroughService.dispatch(gate, player, mode);
    }

    /**
     * PandiO/knk-workspace#167: the player's preferred style when the door offers it, otherwise the door's
     * default style ({@link GatePassThroughStyles}). Walk-path (INSTANT_OPEN) still needs the extra
     * knk.gate.passthrough.instant node (or gate admin); without it the door's default is used (silent
     * fallback, not a denial - the gate should "just work" at whatever tier the player is permitted).
     */
    GatePassThroughMethod resolveMode(Player player, CachedGateDoor gate, boolean isAdmin) {
        PlayerUserData userData = userManager.getCachedUser(player.getUniqueId());
        GatePassThroughMethod preferred = userData != null
            ? userData.gatePassThroughMethodDefault()
            : GatePassThroughMethod.DEFAULT;
        boolean mayUseInstant = isAdmin || GatePassThroughRules.mayUseInstant(player);
        return GatePassThroughStyles.resolve(gate, preferred, mayUseInstant);
    }
}
