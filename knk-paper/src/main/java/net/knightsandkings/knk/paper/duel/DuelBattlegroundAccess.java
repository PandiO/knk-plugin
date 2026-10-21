package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.regions.access.DomainAccessExemptions;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Battleground entry and exit for duelists (arena duels DESIGN §8.2, D16), through P0's
 * {@link DomainAccessExemptions}: battlegrounds keep {@code AllowEntry = false}, and the two duelists of a match get
 * an entry exemption for their allocated battleground from the countdown teleport until the exit teleport. In the
 * countdown and the fight they may not leave it (walk, pearl, chorus fruit, elytra, vehicle); the runtime's own exit
 * teleport carries a one-shot pass. Memory only: a crash leaves nothing behind.
 */
public final class DuelBattlegroundAccess implements DomainAccessExemptions {

    static final String CONFINED = "You can't leave the battleground during a duel.";

    private record Grant(String regionId, Predicate<UUID> confined) {}

    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();
    private final Set<UUID> exitPasses = ConcurrentHashMap.newKeySet();

    /**
     * Let {@code playerId} into {@code regionId}; {@code confined} answers whether they must stay inside right now
     * (the match is in the countdown or the fight).
     */
    public void grant(UUID playerId, String regionId, Predicate<UUID> confined) {
        if (playerId == null || regionId == null) return;
        exitPasses.remove(playerId);
        grants.put(playerId, new Grant(regionId, confined == null ? id -> false : confined));
    }

    /** The one-shot pass for the runtime's exit teleport: the next exit is allowed. */
    public void passExit(UUID playerId) {
        if (grants.containsKey(playerId)) exitPasses.add(playerId);
    }

    /** Every end path: the exemption and the pass go. */
    public void revoke(UUID playerId) {
        grants.remove(playerId);
        exitPasses.remove(playerId);
    }

    public boolean hasGrant(UUID playerId) {
        return grants.containsKey(playerId);
    }

    @Override
    public boolean exemptFromEntry(UUID playerId, String regionId) {
        Grant grant = grants.get(playerId);
        return grant != null && grant.regionId().equalsIgnoreCase(regionId);
    }

    @Override
    public Optional<String> confinement(UUID playerId, String regionId) {
        Grant grant = grants.get(playerId);
        if (grant == null || !grant.regionId().equalsIgnoreCase(regionId)) return Optional.empty();
        if (exitPasses.contains(playerId)) return Optional.empty();
        return grant.confined().test(playerId) ? Optional.of(CONFINED) : Optional.empty();
    }
}
