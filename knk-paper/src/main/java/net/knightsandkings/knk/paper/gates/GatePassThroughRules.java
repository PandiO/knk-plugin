package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.entity.Player;

/**
 * Who may pass through a closed gate door by right-clicking it (road navigation plan §2 R25: the
 * predicate {@code GatePassThroughConsequenceListener} used to hold inline, so navigation can ask the
 * same question when it decides whether a gate edge is open for a player - DESIGN §6.7).
 */
public final class GatePassThroughRules {
    public static final String ADMIN_NODE = "knk.gate.admin";
    public static final String USE_NODE = "knk.gate.passthrough.use";

    private GatePassThroughRules() {
    }

    /** A gate admin passes any door; anyone else needs the door to allow pass-through and the use node. */
    public static boolean canPass(Player player, CachedGateDoor gate) {
        return canPass(isAdmin(player), player.hasPermission(USE_NODE), gate);
    }

    /**
     * The same rule with the player's two nodes read beforehand (road navigation: the router reads
     * them once on the main thread and judges every door of the network off it).
     */
    public static boolean canPass(boolean admin, boolean useNode, CachedGateDoor gate) {
        if (admin) {
            return true;
        }
        return gate.isEffectivelyAllowPassThrough() && useNode;
    }

    public static boolean isAdmin(Player player) {
        return player.hasPermission(ADMIN_NODE);
    }
}
