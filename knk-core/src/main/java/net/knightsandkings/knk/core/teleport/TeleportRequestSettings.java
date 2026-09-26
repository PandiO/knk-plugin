package net.knightsandkings.knk.core.teleport;

/**
 * The {@code teleport.request:} block of the plugin's config.yml (docs/specs/teleport/DESIGN.md
 * §3.5/§3.11, Phase 3). Out-of-range values are clamped so a typo can't make a request expire
 * instantly or never be accepted.
 *
 * @param expireSeconds   how long a request can be answered (v1: 30 s); at least 1
 * @param cooldownSeconds how long a player waits between sending two requests
 * @param maxIncoming     pending requests one player can have; the oldest is dropped past it; at least 1
 * @param priceCoins      coins a request costs the requester (v1 designed 10000, never charged; 0 =
 *                        free). Charged server-side when the teleport commits, through the same
 *                        charge path as warps (DESIGN §3.5, Phase 5), and refunded if it then fails.
 */
public record TeleportRequestSettings(
    int expireSeconds,
    int cooldownSeconds,
    int maxIncoming,
    int priceCoins
) {
    public TeleportRequestSettings {
        expireSeconds = Math.max(1, expireSeconds);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        maxIncoming = Math.max(1, maxIncoming);
        priceCoins = Math.max(0, priceCoins);
    }

    /** The DESIGN §3.11 defaults, used when config.yml has no {@code teleport.request:} block. */
    public static TeleportRequestSettings defaults() {
        return new TeleportRequestSettings(30, 10, 5, 0);
    }

    /** Whether requests cost something (charged to the requester when the teleport commits). */
    public boolean isPaid() {
        return priceCoins > 0;
    }
}
