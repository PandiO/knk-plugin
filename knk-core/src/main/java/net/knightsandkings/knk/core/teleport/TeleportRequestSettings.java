package net.knightsandkings.knk.core.teleport;

/**
 * The {@code teleport.request:} block of the plugin's config.yml (docs/specs/teleport/DESIGN.md
 * §3.5/§3.11, Phase 3). Out-of-range values are clamped so a typo can't make a request expire
 * instantly or never be accepted.
 *
 * @param expireSeconds   how long a request can be answered (v1: 30 s); at least 1
 * @param cooldownSeconds how long a player waits between sending two requests
 * @param maxIncoming     pending requests one player can have; the oldest is dropped past it; at least 1
 * @param priceCoins      coins a request would cost the requester (v1 designed 10000, never charged).
 *                        Only 0 is supported until the server-side charge path exists (DESIGN §3.5:
 *                        the same path as warps, Phase 5) - a non-zero price turns requests off
 *                        rather than charging from the plugin.
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

    /** Whether requests would cost something - not supported yet, see {@link #priceCoins()}. */
    public boolean isPaid() {
        return priceCoins > 0;
    }
}
