package net.knightsandkings.knk.core.domain.teleport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * A player's teleport fees and cooldowns from their permission groups (knk-web-api
 * {@code GET api/teleport-destinations/policy?userId=}, Linear KNG-41). Each kind's values come
 * from the first of the player's groups that sets them (highest Weight first, each group followed
 * by its parents). The plugin uses the cooldowns, and the prices only to know whether a
 * {@code /tpa} or {@code /spawn} needs a charge; the charge routes price it again server-side.
 */
public record KnkTeleportPolicy(Kind request, Kind warp, Kind spawn) {

    /** No group sets anything: today's defaults everywhere. */
    public static final KnkTeleportPolicy DEFAULT = new KnkTeleportPolicy(Kind.NONE, Kind.NONE, Kind.NONE);

    public KnkTeleportPolicy {
        request = request != null ? request : Kind.NONE;
        warp = warp != null ? warp : Kind.NONE;
        spawn = spawn != null ? spawn : Kind.NONE;
    }

    /** The settings of {@code kind}'s teleports; {@link Kind#NONE} for kinds groups can't set (staff, /back). */
    public Kind of(TeleportKind kind) {
        return switch (kind) {
            case REQUEST -> request;
            case WARP -> warp;
            case SPAWN -> spawn;
            default -> Kind.NONE;
        };
    }

    /**
     * One kind's settings.
     *
     * @param priceMode       "None" (the default price), "Fixed" or "Multiplier"
     * @param priceMultiplier Multiplier: factor on the default price
     * @param priceCoins      Fixed: coins
     * @param priceGems       Fixed: gems
     * @param priceExperience Fixed: XP
     * @param cooldownSeconds replaces {@code teleport.cooldown-seconds}; null = not set
     */
    public record Kind(String priceMode, Double priceMultiplier, Integer priceCoins, Integer priceGems,
                       Integer priceExperience, Integer cooldownSeconds) {

        public static final Kind NONE = new Kind("None", null, null, null, null, null);

        public Kind {
            priceMode = priceMode != null ? priceMode : "None";
        }

        public boolean isFixed() {
            return "fixed".equals(priceMode.toLowerCase(Locale.ROOT));
        }

        public boolean isMultiplier() {
            return "multiplier".equals(priceMode.toLowerCase(Locale.ROOT));
        }

        /** Whether a group sets this kind's price. */
        public boolean priced() {
            return isFixed() || isMultiplier();
        }

        /**
         * Whether this kind costs anything, given its default coin price (a /tpa's
         * {@code teleport.request.price-coins}; 0 for /spawn). Rounding like the server (half up).
         */
        public boolean costsAnything(int defaultCoins) {
            if (isFixed()) {
                return orZero(priceCoins) > 0 || orZero(priceGems) > 0 || orZero(priceExperience) > 0;
            }
            if (isMultiplier()) {
                return Math.round(defaultCoins * (priceMultiplier != null ? priceMultiplier : 1d)) > 0;
            }
            return defaultCoins > 0;
        }

        /** "100 coins and 1 gem", given the default coin price; "free" when nothing. */
        public String priceLabel(int defaultCoins) {
            if (isFixed()) {
                List<String> parts = new ArrayList<>();
                if (orZero(priceCoins) > 0) {
                    parts.add(TeleportPayment.amount(priceCoins, "Coins"));
                }
                if (orZero(priceGems) > 0) {
                    parts.add(TeleportPayment.amount(priceGems, "Gems"));
                }
                if (orZero(priceExperience) > 0) {
                    parts.add(TeleportPayment.amount(priceExperience, "Experience"));
                }
                return parts.isEmpty() ? "free" : TeleportPayment.describe(parts);
            }
            long coins = isMultiplier()
                ? Math.round(defaultCoins * (priceMultiplier != null ? priceMultiplier : 1d))
                : defaultCoins;
            return coins > 0 ? TeleportPayment.amount(coins, "Coins") : "free";
        }

        public OptionalInt cooldown() {
            return cooldownSeconds != null ? OptionalInt.of(Math.max(0, cooldownSeconds)) : OptionalInt.empty();
        }

        private static int orZero(Integer value) {
            return value != null ? value : 0;
        }
    }
}
