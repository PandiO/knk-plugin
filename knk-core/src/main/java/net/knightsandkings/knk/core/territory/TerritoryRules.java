package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * The territory rules of the game server (territory DESIGN § 1-3, decisions G10 and G11; PandiO/knk-workspace#689). They
 * are the API's {@code TerritoryRules.Resolve} (knk-web-api {@code Services/Territory/TerritoryRules.cs}) on this side:
 * the holders of the highest rank among those whose range reaches a position; one owns it, two or more contest it unless
 * the area was awarded to one of them. Ranges come computed from the API (base + power × factor, × the capital
 * multiplier), so the plugin never recomputes them.
 */
public final class TerritoryRules {

    private TerritoryRules() {
    }

    /** The verdict for a position reached by {@code claims}, with {@code awardedTo} the award covering it (or null). */
    public static TerritoryVerdict resolve(Collection<TerritoryHolder> claims, TerritoryHolder awardedTo) {
        if (claims == null || claims.isEmpty()) {
            return TerritoryVerdict.WILDERNESS;
        }
        int top = 0;
        for (TerritoryHolder holder : claims) {
            top = Math.max(top, holder.rank());
        }
        TreeSet<TerritoryHolder> best = new TreeSet<>();
        for (TerritoryHolder holder : claims) {
            if (holder.rank() == top) {
                best.add(holder);
            }
        }
        List<TerritoryHolder> sorted = new ArrayList<>(best);
        if (sorted.size() == 1) {
            return new TerritoryVerdict(sorted.get(0), sorted);
        }
        if (awardedTo != null && best.contains(awardedTo)) {
            return new TerritoryVerdict(awardedTo, sorted);
        }
        return new TerritoryVerdict(null, sorted);
    }

    /**
     * An overlap of these two is a conflict the API records: different holders of equal rank. A town against crown land is
     * not (crown land wins), nor a holder against itself.
     */
    public static boolean isConflictPair(TerritoryHolder a, TerritoryHolder b) {
        return a != null && b != null && !a.equals(b) && a.rank() == b.rank();
    }
}
