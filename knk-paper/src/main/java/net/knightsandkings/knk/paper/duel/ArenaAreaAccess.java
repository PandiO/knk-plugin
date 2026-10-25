package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.duel.ArenaAreaAccessRules;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The duel runtime's own access rule for the arena's areas (arena duels DESIGN §8.2, rev. 6, developer 2026-10-24),
 * replacing P4's {@code DuelBattlegroundAccess} on P0's domain-access exemptions: areas are not domains any more. The
 * runtime grants the two duelists their allocated battleground (by area id) from the countdown teleport until the exit
 * teleport, keeps them in during the countdown and the fight, and gives a one-shot pass for its own exit teleport. The
 * lobby and spectator rules come from {@link ArenaAreaAccessRules} with the runtime's match state.
 * {@link ArenaAreaAccessHandler} asks {@link #evaluate} for every WorldGuard crossing. Memory only: a crash leaves
 * nothing behind.
 */
public final class ArenaAreaAccess implements ArenaAreaAccessRules.State {

    private record Grant(int areaId, Predicate<UUID> confined) {}

    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();
    private final Set<UUID> exitPasses = ConcurrentHashMap.newKeySet();
    private volatile Supplier<List<KnkArena>> arenas = List::of;
    private volatile IntPredicate lobbyReserved = arenaId -> false;
    private volatile BiPredicate<UUID, Integer> duelistOf = (player, arenaId) -> false;

    /** The cached arenas the rule looks the areas up in. */
    public void setArenas(Supplier<List<KnkArena>> arenas) {
        this.arenas = arenas != null ? arenas : List::of;
    }

    /** The runtime's match state for the lobby rule (DuelService binds itself). */
    public void bindRuntime(IntPredicate lobbyReserved, BiPredicate<UUID, Integer> duelistOf) {
        this.lobbyReserved = lobbyReserved != null ? lobbyReserved : arenaId -> false;
        this.duelistOf = duelistOf != null ? duelistOf : (player, arenaId) -> false;
    }

    /**
     * Let {@code playerId} into the battleground area {@code areaId}; {@code confined} answers whether they must stay
     * inside right now (the match is in the countdown or the fight).
     */
    public void grant(UUID playerId, int areaId, Predicate<UUID> confined) {
        if (playerId == null) return;
        exitPasses.remove(playerId);
        grants.put(playerId, new Grant(areaId, confined == null ? id -> false : confined));
    }

    /** The one-shot pass for the runtime's exit teleport: the next exit is allowed. */
    public void passExit(UUID playerId) {
        if (grants.containsKey(playerId)) exitPasses.add(playerId);
    }

    /** Every end path: the grant and the pass go. */
    public void revoke(UUID playerId) {
        grants.remove(playerId);
        exitPasses.remove(playerId);
    }

    public boolean hasGrant(UUID playerId) {
        return grants.containsKey(playerId);
    }

    /** The battleground area granted to {@code playerId}, if any. */
    public Optional<Integer> grantedArea(UUID playerId) {
        Grant grant = grants.get(playerId);
        return grant == null ? Optional.empty() : Optional.of(grant.areaId());
    }

    /**
     * The rule for one crossing (no staff bypass here: the handler checks it).
     *
     * @param origin      region ids where the move starts
     * @param destination region ids where it goes
     */
    public Optional<ArenaAreaAccessRules.Refusal> evaluate(UUID playerId, Collection<String> origin,
                                                           Collection<String> destination, String worldName) {
        List<KnkArena> list = arenas.get();
        return ArenaAreaAccessRules.evaluate(playerId, origin, destination, worldName, list == null ? List.of() : list, this);
    }

    @Override
    public boolean allocatedTo(UUID player, int battlegroundAreaId) {
        Grant grant = grants.get(player);
        return grant != null && grant.areaId() == battlegroundAreaId;
    }

    @Override
    public boolean confined(UUID player, int battlegroundAreaId) {
        Grant grant = grants.get(player);
        if (grant == null || grant.areaId() != battlegroundAreaId || exitPasses.contains(player)) return false;
        return grant.confined().test(player);
    }

    @Override
    public boolean lobbyReserved(int arenaId) {
        return lobbyReserved.test(arenaId);
    }

    @Override
    public boolean duelistOf(UUID player, int arenaId) {
        return duelistOf.test(player, arenaId);
    }
}
