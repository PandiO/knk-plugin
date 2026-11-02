package net.knightsandkings.knk.core.freeze;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who got flight allowed only because they are frozen (PandiO/knk-workspace#155), and what their own setting was.
 * <p>
 * A frozen player is held in place, also in mid-air, and the server's flight check kicks a player who hangs in the
 * air without being allowed to fly ("Flying is not enabled on this server"). So while a player is frozen the plugin
 * allows flight for that player only (in place of {@code allow-flight=true} for the whole server), and hands their
 * own setting back when the last freeze ends. An enchantment freeze and a staff {@code /freeze} can overlap; the grant
 * holds until both ended.
 * <p>
 * Pure bookkeeping; the Paper side ({@code FreezeFlightGuard}) applies the answers to the player.
 */
public final class FreezeFlightLedger {

    /** Why a player is frozen. */
    public enum Source {
        /** The freeze enchantment's effect (timed). */
        ENCHANTMENT,
        /** A staff /freeze (until /unfreeze). */
        ADMIN
    }

    private record Grant(boolean ownAllowFlight, EnumSet<Source> sources) {}

    private final Map<UUID, Grant> grants = new HashMap<>();

    /**
     * Records a freeze.
     *
     * @param currentAllowFlight the player's flight setting right now
     * @return true when flight has to be allowed now (the player's first freeze, and they may not fly yet)
     */
    public synchronized boolean grant(UUID playerId, Source source, boolean currentAllowFlight) {
        if (playerId == null || source == null) {
            return false;
        }
        Grant existing = grants.get(playerId);
        if (existing != null) {
            existing.sources().add(source);
            return false;
        }
        grants.put(playerId, new Grant(currentAllowFlight, EnumSet.of(source)));
        return !currentAllowFlight;
    }

    /**
     * Records the end of a freeze.
     *
     * @return the player's own flight setting when this was their last freeze (hand it back), empty while another
     *         freeze still holds or when this one never allowed flight
     */
    public synchronized Optional<Boolean> release(UUID playerId, Source source) {
        if (playerId == null || source == null) {
            return Optional.empty();
        }
        Grant existing = grants.get(playerId);
        if (existing == null || !existing.sources().remove(source)) {
            return Optional.empty();
        }
        if (!existing.sources().isEmpty()) {
            return Optional.empty();
        }
        grants.remove(playerId);
        return Optional.of(existing.ownAllowFlight());
    }

    /** Ends every freeze of the player (quit, plugin disable); the player's own setting, when there was a grant. */
    public synchronized Optional<Boolean> releaseAll(UUID playerId) {
        Grant existing = playerId == null ? null : grants.remove(playerId);
        return existing == null ? Optional.empty() : Optional.of(existing.ownAllowFlight());
    }

    public synchronized boolean isGranted(UUID playerId) {
        return playerId != null && grants.containsKey(playerId);
    }

    public synchronized Set<UUID> players() {
        return Set.copyOf(grants.keySet());
    }

    /**
     * Whether handing back the player's own setting means switching flight off now: only when they could not fly
     * before, flight is still on (nobody else turned it off meanwhile, e.g. a siege's preparation) and their game mode
     * doesn't fly by itself (creative, spectator).
     */
    public static boolean shouldDisallowOnRelease(boolean ownAllowFlight, boolean currentAllowFlight, boolean flyingGameMode) {
        return !ownAllowFlight && currentAllowFlight && !flyingGameMode;
    }
}
