package net.knightsandkings.knk.paper.discovery;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * Who can discover places right now (docs/specs/domain-discovery DESIGN.md §3.6, D7). A player is
 * excluded while their account is loading, in staff/owner mode (which is also vanish), frozen, in
 * an excluded game mode (creative/spectator by default) or taking part in a siege. Excluded players
 * simply discover nothing: they discover a place the next time they are in it without being excluded.
 *
 * <p>No permission node: KnkPermissible grants ops every node (an "exempt" node would exempt every op)
 * and fails closed on a cold cache (a positive node would silently drop join discoveries).
 */
public final class DiscoveryEligibility {

    /** Why a player is excluded, or {@link #NONE}. */
    public enum Exclusion {
        NONE,
        DISABLED,
        LOADING,
        MODE,
        FROZEN,
        GAME_MODE,
        SIEGE
    }

    private final boolean enabled;
    private final Predicate<UUID> loading;
    private final Function<Player, ActiveMode> activeMode;
    private final Predicate<UUID> frozen;
    private final Set<GameMode> excludedGameModes;
    private final boolean excludeSiegeParticipants;
    /**
     * Siege participation. The siege minigame isn't on trunk yet, so nobody is a participant until it
     * plugs in its check ({@code SiegeService.isParticipant}) through {@link #setSiegeParticipantCheck}.
     */
    private volatile Predicate<UUID> siegeParticipant = uuid -> false;

    public DiscoveryEligibility(
            boolean enabled,
            Predicate<UUID> loading,
            Function<Player, ActiveMode> activeMode,
            Predicate<UUID> frozen,
            Set<GameMode> excludedGameModes,
            boolean excludeSiegeParticipants) {
        this.enabled = enabled;
        this.loading = Objects.requireNonNull(loading, "loading");
        this.activeMode = Objects.requireNonNull(activeMode, "activeMode");
        this.frozen = Objects.requireNonNull(frozen, "frozen");
        this.excludedGameModes = excludedGameModes == null ? Set.of() : Set.copyOf(excludedGameModes);
        this.excludeSiegeParticipants = excludeSiegeParticipants;
    }

    /** Hook for the siege minigame: players this returns true for discover nothing. */
    public void setSiegeParticipantCheck(Predicate<UUID> check) {
        this.siegeParticipant = check == null ? uuid -> false : check;
    }

    public boolean isEligible(Player player) {
        return exclusion(player) == Exclusion.NONE;
    }

    /** Main thread (reads the player's game mode). */
    public Exclusion exclusion(Player player) {
        if (!enabled) {
            return Exclusion.DISABLED;
        }
        UUID uuid = player.getUniqueId();
        if (loading.test(uuid)) {
            return Exclusion.LOADING;
        }
        ActiveMode mode = activeMode.apply(player);
        if (mode != null && mode != ActiveMode.NONE) {
            return Exclusion.MODE;
        }
        if (frozen.test(uuid)) {
            return Exclusion.FROZEN;
        }
        if (excludedGameModes.contains(player.getGameMode())) {
            return Exclusion.GAME_MODE;
        }
        if (excludeSiegeParticipants && siegeParticipant.test(uuid)) {
            return Exclusion.SIEGE;
        }
        return Exclusion.NONE;
    }
}
