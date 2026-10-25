package net.knightsandkings.knk.core.duel;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;

/**
 * Who may cross into or out of an arena's areas (arena duels DESIGN.md §8.2, rev. 6, developer 2026-10-24). Areas are
 * not domains, so the domain-access rule doesn't see them; the duel runtime asks this rule for every crossing through
 * its own WorldGuard session handler. Pure: the caller passes the region ids at the origin and the destination and the
 * runtime's {@link State}; staff bypass is the caller's.
 *
 * <p><b>Union containment</b> (KNG-253's pattern): an area is <em>entered</em> when none of its regions is at the origin
 * and one is at the destination, and <em>left</em> the other way round, so moving between two parts of one battleground
 * is neither.
 * <ul>
 *   <li>{@code BATTLEGROUND}: entering only for a duelist the battleground is allocated to (countdown teleport to exit
 *       teleport); leaving refused for that duelist while confined (countdown and fight, unless the runtime's one-shot
 *       exit pass is set).</li>
 *   <li>{@code LOBBY}: while a match of the arena is in its warmup, entering only for that arena's duelists; open
 *       otherwise.</li>
 *   <li>{@code SPECTATOR}: free.</li>
 * </ul>
 * Disabled areas follow the same rules (a disabled battleground is never allocated, so it refuses everyone).
 */
public final class ArenaAreaAccessRules {

    public static final String BATTLEGROUND_RESERVED = "This battleground is reserved for a duel.";
    public static final String CONFINED = "You can't leave the battleground during a duel.";
    public static final String LOBBY_RESERVED = "The arena lobby is reserved for the duelists during a warmup.";

    /** What the runtime knows; every answer is about now. */
    public interface State {
        /** Whether {@code battlegroundAreaId} is allocated to {@code player}'s match (from the countdown teleport). */
        boolean allocatedTo(UUID player, int battlegroundAreaId);

        /** Whether {@code player} must stay inside {@code battlegroundAreaId} now (countdown or fight, no exit pass). */
        boolean confined(UUID player, int battlegroundAreaId);

        /** Whether a match of {@code arenaId} is in its warmup (its lobby is reserved). */
        boolean lobbyReserved(int arenaId);

        /** Whether {@code player} is a duelist of a running match of {@code arenaId}. */
        boolean duelistOf(UUID player, int arenaId);
    }

    /** A refused crossing: the area, whether it was an entry, and the player's message. */
    public record Refusal(KnkArenaArea area, boolean entry, String message) {
    }

    private ArenaAreaAccessRules() {
    }

    /**
     * @param origin      the WorldGuard region ids at the position the move starts from (any case)
     * @param destination the region ids at the position it goes to
     * @param worldName   the world of both positions (a cross-world teleport is judged on its destination world's areas
     *                    for entering and its origin world's for leaving: pass the ids accordingly)
     */
    public static Optional<Refusal> evaluate(UUID player, Collection<String> origin, Collection<String> destination,
                                             String worldName, List<KnkArena> arenas, State state) {
        Objects.requireNonNull(state, "state");
        if (player == null || arenas == null || arenas.isEmpty()) {
            return Optional.empty();
        }
        Set<String> from = lower(origin);
        Set<String> to = lower(destination);
        if (from.equals(to)) {
            return Optional.empty();
        }
        for (KnkArena arena : arenas) {
            for (KnkArenaArea area : arena.areas()) {
                boolean wasIn = inArea(area, from, worldName);
                boolean isIn = inArea(area, to, worldName);
                if (wasIn == isIn) {
                    continue;
                }
                Optional<Refusal> refusal = isIn
                        ? entering(player, arena, area, state)
                        : leaving(player, area, state);
                if (refusal.isPresent()) {
                    return refusal;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Refusal> entering(UUID player, KnkArena arena, KnkArenaArea area, State state) {
        return switch (area.kind()) {
            case BATTLEGROUND -> state.allocatedTo(player, area.id())
                    ? Optional.empty() : Optional.of(new Refusal(area, true, BATTLEGROUND_RESERVED));
            case LOBBY -> state.lobbyReserved(arena.id()) && !state.duelistOf(player, arena.id())
                    ? Optional.of(new Refusal(area, true, LOBBY_RESERVED)) : Optional.empty();
            case SPECTATOR -> Optional.empty();
        };
    }

    private static Optional<Refusal> leaving(UUID player, KnkArenaArea area, State state) {
        return area.kind() == KnkArenaArea.Kind.BATTLEGROUND && state.confined(player, area.id())
                ? Optional.of(new Refusal(area, false, CONFINED)) : Optional.empty();
    }

    /** Whether any of {@code area}'s regions is in {@code ids} (in {@code worldName}). */
    public static boolean inArea(KnkArenaArea area, Set<String> lowerIds, String worldName) {
        if (lowerIds.isEmpty()) {
            return false;
        }
        for (var region : area.regions()) {
            if (region.wgRegionId() != null && lowerIds.contains(region.wgRegionId().toLowerCase(Locale.ROOT))
                    && region.matches(worldName, region.wgRegionId())) {
                return true;
            }
        }
        return false;
    }

    /** The region ids in lower case (WorldGuard ids ignore case). */
    public static Set<String> lower(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream().filter(Objects::nonNull).map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
