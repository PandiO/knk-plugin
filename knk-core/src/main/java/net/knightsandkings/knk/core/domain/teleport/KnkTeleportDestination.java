package net.knightsandkings.knk.core.domain.teleport;

import java.util.Locale;
import java.util.Objects;

/**
 * One {@code /warp} destination as one player sees it (knk-web-api {@code TeleportDestinationDto},
 * docs/specs/teleport/DESIGN.md §3.7): a Town, District or Structure whose {@code Location} is the
 * arrival point. The server evaluated the requirements in DESIGN §3.7.2's order - title, premium
 * tier, discovery, price - and {@code lockCode}/{@code lockReason} name the first one the player
 * doesn't meet. The plugin only layers its bypass nodes on top ({@link #lockCode(boolean, boolean)});
 * the charge call re-checks everything, so a stale cached copy can't grant anything.
 *
 * @param requirementsMet title, premium tier and discovery requirements all met
 * @param canAfford       the player has at least {@code priceGems} gems
 */
public record KnkTeleportDestination(
    int domainId,
    String name,
    String domainType,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    int priceGems,
    String minTitleName,
    String minPremiumTierName,
    boolean requiresDiscovery,
    boolean available,
    boolean requirementsMet,
    boolean canAfford,
    String lockCode,
    String lockReason
) {
    public static final String INSUFFICIENT_GEMS = "InsufficientGems";

    public KnkTeleportDestination {
        Objects.requireNonNull(name, "name must not be null");
        domainType = domainType != null ? domainType : "Domain";
        priceGems = Math.max(0, priceGems);
    }

    /** The lock left once the player's bypass nodes are applied; null when they can warp here. */
    public String lockCode(boolean bypassRequirements, boolean bypassCost) {
        if (!requirementsMet && !bypassRequirements) {
            return lockCode != null ? lockCode : "Locked";
        }
        if (!canAfford && !bypassCost && priceGems > 0) {
            return INSUFFICIENT_GEMS;
        }
        return null;
    }

    /** Player-facing text for {@link #lockCode(boolean, boolean)}; null when unlocked. */
    public String lockReason(boolean bypassRequirements, boolean bypassCost) {
        String code = lockCode(bypassRequirements, bypassCost);
        if (code == null) {
            return null;
        }
        if (INSUFFICIENT_GEMS.equals(code) && !INSUFFICIENT_GEMS.equals(lockCode)) {
            return "You don't have enough gems to teleport to this location!";
        }
        return lockReason != null ? lockReason : "Locked";
    }

    public boolean isAvailable(boolean bypassRequirements, boolean bypassCost) {
        return lockCode(bypassRequirements, bypassCost) == null;
    }

    /** The {@code type:name} form ({@code town:Kardenna}) that picks one of several same-named places. */
    public String qualifiedName() {
        return domainType.toLowerCase(Locale.ROOT) + ":" + name;
    }
}
