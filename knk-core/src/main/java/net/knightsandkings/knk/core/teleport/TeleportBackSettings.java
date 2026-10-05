package net.knightsandkings.knk.core.teleport;

import java.util.EnumMap;
import java.util.Map;

/**
 * The {@code teleport.back:} block of the plugin's config.yml (docs/specs/teleport/DESIGN.md §3.11,
 * Phase 7; Linear KNG-42). {@code /back} returns a player to the latest place they may go back to -
 * a death, or where they stood before a warp, teleport or {@code /spawn} ({@link BackKind}) - for a
 * few minutes, never to a siege death or across a siege teleport.
 *
 * @param enabled             whether {@code /back} is available at all
 * @param expireSeconds       how long after the death or teleport {@code /back} can be started (v1 perk
 *                            text: 5 minutes); at least 1. The default for every kind
 * @param expireSecondsByKind per-kind overrides of {@code expireSeconds} (KNG-42); kinds left out use
 *                            {@code expireSeconds}; each at least 1
 * @param priceCoins          coins a player's own {@code /back} costs (0 = free), charged by the web API
 *                            when the teleport happens (developer decision 2026-10-05: a flat fee)
 */
public record TeleportBackSettings(boolean enabled, int expireSeconds, Map<BackKind, Integer> expireSecondsByKind,
                                   int priceCoins) {

    /** Developer decision Q5: available for 5 minutes after a death. */
    public static final int DEFAULT_EXPIRE_SECONDS = 300;

    public TeleportBackSettings {
        expireSeconds = Math.max(1, expireSeconds);
        EnumMap<BackKind, Integer> overrides = new EnumMap<>(BackKind.class);
        if (expireSecondsByKind != null) {
            expireSecondsByKind.forEach((kind, seconds) -> {
                if (kind != null && seconds != null) {
                    overrides.put(kind, Math.max(1, seconds));
                }
            });
        }
        expireSecondsByKind = Map.copyOf(overrides);
        priceCoins = Math.max(0, priceCoins);
    }

    /** One expiry for every kind, free. */
    public TeleportBackSettings(boolean enabled, int expireSeconds) {
        this(enabled, expireSeconds, Map.of(), 0);
    }

    /** The defaults, used when config.yml has no {@code teleport.back:} block. */
    public static TeleportBackSettings defaults() {
        return new TeleportBackSettings(true, DEFAULT_EXPIRE_SECONDS);
    }

    /** How long an entry of {@code kind} can be used: its override, else {@link #expireSeconds}. */
    public int expireSeconds(BackKind kind) {
        Integer override = expireSecondsByKind.get(kind);
        return override != null ? override : expireSeconds;
    }

    /** Whether a player's own {@code /back} costs coins. */
    public boolean isPaid() {
        return priceCoins > 0;
    }
}
