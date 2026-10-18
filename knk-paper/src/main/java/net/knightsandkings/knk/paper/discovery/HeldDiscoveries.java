package net.knightsandkings.knk.paper.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.users.TitleChangeResult;

/**
 * Discovery announcements held back during a new player's quiet minutes (KNG-128). The grants are
 * already recorded and rewarded by the API; only the chat, sound and title moment waits. When the
 * window closes, everything held for a player is shown as one summary ({@link #merge}).
 *
 * <p>Main thread only, like {@link DiscoveryEffects}. Free of Bukkit types so it is unit testable.
 */
final class HeldDiscoveries {
    private final Map<UUID, List<DiscoveryGrantResult>> held = new HashMap<>();

    void hold(UUID playerId, DiscoveryGrantResult result) {
        held.computeIfAbsent(playerId, id -> new ArrayList<>()).add(result);
    }

    boolean isEmpty() {
        return held.isEmpty();
    }

    /** Removes and returns, merged, everything held for players who are no longer quiet. */
    Map<UUID, DiscoveryGrantResult> releaseWhere(Predicate<UUID> stillQuiet) {
        Map<UUID, DiscoveryGrantResult> released = new LinkedHashMap<>();
        Iterator<Map.Entry<UUID, List<DiscoveryGrantResult>>> entries = held.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, List<DiscoveryGrantResult>> entry = entries.next();
            if (!stillQuiet.test(entry.getKey())) {
                released.put(entry.getKey(), merge(entry.getValue()));
                entries.remove();
            }
        }
        return released;
    }

    /**
     * One result for several grant answers: all places, summed totals, the last balances and the last
     * title change. The multipliers can differ between answers, so the merged result carries no
     * breakdown (base 0): the summary shows each total on its own.
     */
    static DiscoveryGrantResult merge(List<DiscoveryGrantResult> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        List<DiscoveryGrant> granted = new ArrayList<>();
        List<Integer> alreadyDiscovered = new ArrayList<>();
        int coins = 0;
        int gems = 0;
        int exp = 0;
        TitleChangeResult titleChange = null;
        Integer titleBracketId = null;
        DiscoveryGrantResult last = results.get(results.size() - 1);
        for (DiscoveryGrantResult result : results) {
            granted.addAll(result.granted());
            alreadyDiscovered.addAll(result.alreadyDiscovered());
            coins += result.totalCoins();
            gems += result.totalGems();
            exp += result.totalExp();
            if (result.titleChange() != null) {
                titleChange = result.titleChange();
            }
            if (result.titleBracketId() != null) {
                titleBracketId = result.titleBracketId();
            }
        }
        return new DiscoveryGrantResult(granted, alreadyDiscovered, List.of(), coins, gems, exp, 0, 0, 0,
            List.of(), List.of(), List.of(), titleBracketId, last.newCoins(), last.newGems(), last.newExperiencePoints(),
            titleChange);
    }
}
