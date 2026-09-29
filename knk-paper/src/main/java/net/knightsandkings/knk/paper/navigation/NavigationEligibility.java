package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * Who may start (and keep) a navigation (road navigation plan Phase 4 task 9, reuse map R23: the
 * {@code DiscoveryEligibility} shape over the same services). A player is refused while their
 * account is loading, while frozen, and while they are a member of a siege lobby (plan D2:
 * navigation ends when the player joins a siege). Vanished staff may navigate.
 */
public final class NavigationEligibility {

    /** Why a player is refused, or {@link #NONE}. */
    public enum Exclusion {
        NONE,
        LOADING,
        FROZEN,
        SIEGE
    }

    private final Predicate<UUID> loading;
    private final Predicate<UUID> frozen;
    private final Predicate<UUID> inSiege;

    public NavigationEligibility(Predicate<UUID> loading, Predicate<UUID> frozen, Predicate<UUID> inSiege) {
        this.loading = Objects.requireNonNull(loading, "loading");
        this.frozen = Objects.requireNonNull(frozen, "frozen");
        this.inSiege = Objects.requireNonNull(inSiege, "inSiege");
    }

    /**
     * The siege hook: a member of any siege lobby ({@link SiegeService#lobbyOf}) of whatever service
     * {@code siege} returns when asked (it may not exist yet, or at all).
     */
    public static Predicate<UUID> siegeMembers(Supplier<SiegeService> siege) {
        Objects.requireNonNull(siege, "siege");
        return uuid -> {
            SiegeService service = siege.get();
            return service != null && service.lobbyOf(uuid).isPresent();
        };
    }

    public boolean isEligible(Player player) {
        return exclusion(player) == Exclusion.NONE;
    }

    public Exclusion exclusion(Player player) {
        UUID uuid = player.getUniqueId();
        if (loading.test(uuid)) {
            return Exclusion.LOADING;
        }
        if (frozen.test(uuid)) {
            return Exclusion.FROZEN;
        }
        if (inSiege.test(uuid)) {
            return Exclusion.SIEGE;
        }
        return Exclusion.NONE;
    }

    /** Whether an active session must end now (the siege rule, checked periodically). */
    public boolean inSiege(UUID uuid) {
        return inSiege.test(uuid);
    }
}
