package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;

import java.util.List;
import java.util.Set;

/**
 * PandiO/knk-workspace#167: which pass-through style a player gets at a door. Every door offers a set of
 * styles plus a default style (both overridable on the structure); the player's own preference
 * ({@code /gate passthrough}, the web profile) is used when the door offers it, otherwise the door's default.
 * The {@code knk.gate.passthrough.instant} node still gates the walk-path style: without it (and without gate
 * admin) that style counts as not offered. Pure rules; {@code GatePassThroughConsequenceListener} applies them.
 */
public final class GatePassThroughStyles {

    /** Tried in this order when neither the preference nor the door default can be used. */
    private static final List<GatePassThroughMethod> FALLBACK_ORDER = List.of(
        GatePassThroughMethod.DEFAULT, GatePassThroughMethod.SNAP, GatePassThroughMethod.TELEPORT);

    private GatePassThroughStyles() {
    }

    /** The style for this player at this door; {@code mayUseInstant} = admin or the instant node. */
    public static GatePassThroughMethod resolve(CachedGateDoor door, GatePassThroughMethod preferred, boolean mayUseInstant) {
        return resolve(preferred, door.getEffectiveAllowedPassThroughMethods(),
            door.getEffectiveDefaultPassThroughMethod(), mayUseInstant);
    }

    /**
     * The preference when offered and usable; else the door default when usable; else the first offered,
     * usable style of animated, snap, teleport; else animated (the pre-#167 fallback when a player without
     * the instant node chose walk-path).
     */
    public static GatePassThroughMethod resolve(GatePassThroughMethod preferred, Set<GatePassThroughMethod> allowed,
                                                GatePassThroughMethod doorDefault, boolean mayUseInstant) {
        if (preferred != null && allowed.contains(preferred) && usable(preferred, mayUseInstant)) {
            return preferred;
        }
        if (doorDefault != null && usable(doorDefault, mayUseInstant)) {
            return doorDefault;
        }
        for (GatePassThroughMethod method : FALLBACK_ORDER) {
            if (allowed.contains(method)) {
                return method;
            }
        }
        return GatePassThroughMethod.DEFAULT;
    }

    private static boolean usable(GatePassThroughMethod method, boolean mayUseInstant) {
        return method != GatePassThroughMethod.INSTANT_OPEN || mayUseInstant;
    }
}
