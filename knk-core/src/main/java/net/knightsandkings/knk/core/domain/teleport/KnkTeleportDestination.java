package net.knightsandkings.knk.core.domain.teleport;

import java.util.ArrayList;
import java.util.List;
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
 * @param priceGems       what the warp costs this player in gems - the domain's price, or their permission
 *                        group's (Linear KNG-41: a multiple of it, or a fixed price)
 * @param requirementsMet title, premium tier and discovery requirements all met
 * @param canAfford       the player can pay the whole price
 * @param priceCoins      coins it costs this player (only a group's fixed price has coins, KNG-41)
 * @param priceExperience XP it costs this player (only a group's fixed price has XP, KNG-41)
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
    String lockReason,
    int priceCoins,
    int priceExperience
) {
    public static final String INSUFFICIENT_GEMS = "InsufficientGems";
    private static final String INSUFFICIENT = "Insufficient";

    public KnkTeleportDestination {
        Objects.requireNonNull(name, "name must not be null");
        domainType = domainType != null ? domainType : "Domain";
        priceGems = Math.max(0, priceGems);
        priceCoins = Math.max(0, priceCoins);
        priceExperience = Math.max(0, priceExperience);
    }

    /** A destination priced in gems only (the domain's own price). */
    public KnkTeleportDestination(int domainId, String name, String domainType, String world, double x, double y,
                                  double z, float yaw, float pitch, int priceGems, String minTitleName,
                                  String minPremiumTierName, boolean requiresDiscovery, boolean available,
                                  boolean requirementsMet, boolean canAfford, String lockCode, String lockReason) {
        this(domainId, name, domainType, world, x, y, z, yaw, pitch, priceGems, minTitleName, minPremiumTierName,
            requiresDiscovery, available, requirementsMet, canAfford, lockCode, lockReason, 0, 0);
    }

    /** Whether the warp costs this player anything. */
    public boolean hasPrice() {
        return priceGems > 0 || priceCoins > 0 || priceExperience > 0;
    }

    /** "10 gems", "100 coins and 1 gem", "free". */
    public String priceLabel() {
        List<String> parts = new ArrayList<>();
        if (priceCoins > 0) {
            parts.add(TeleportPayment.amount(priceCoins, "Coins"));
        }
        if (priceGems > 0) {
            parts.add(TeleportPayment.amount(priceGems, "Gems"));
        }
        if (priceExperience > 0) {
            parts.add(TeleportPayment.amount(priceExperience, "Experience"));
        }
        return parts.isEmpty() ? "free" : TeleportPayment.describe(parts);
    }

    /** The lock left once the player's bypass nodes are applied; null when they can warp here. */
    public String lockCode(boolean bypassRequirements, boolean bypassCost) {
        if (!requirementsMet && !bypassRequirements) {
            return lockCode != null ? lockCode : "Locked";
        }
        if (!canAfford && !bypassCost && hasPrice()) {
            // The server names the currency the player is short of when nothing else locks it.
            return lockCode != null && lockCode.startsWith(INSUFFICIENT) ? lockCode : INSUFFICIENT_GEMS;
        }
        return null;
    }

    /** Player-facing text for {@link #lockCode(boolean, boolean)}; null when unlocked. */
    public String lockReason(boolean bypassRequirements, boolean bypassCost) {
        String code = lockCode(bypassRequirements, bypassCost);
        if (code == null) {
            return null;
        }
        if (code.startsWith(INSUFFICIENT) && !code.equals(lockCode)) {
            // Locked by a requirement the player bypasses; the server didn't say which currency.
            return priceCoins > 0 || priceExperience > 0
                ? "You can't afford the " + priceLabel() + " this teleport costs!"
                : "You don't have enough gems to teleport to this location!";
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
