package net.knightsandkings.knk.core.domain.enchantment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which area ability is hitting a player right now (PandiO/knk-workspace#222). Chaos and Flash Chaos wrap their damage
 * call in {@link #during}; the server fires the victim's death event inside that call, so the death-message listener
 * finds the ability and its user here. Only a death during the hit itself counts: a later death (fall, poison, another
 * player) keeps the vanilla message (developer decision 2026-11-03).
 */
public final class AreaKillAttribution {
    private static final AreaKillAttribution INSTANCE = new AreaKillAttribution();

    private final Map<UUID, Hit> current = new ConcurrentHashMap<>();

    AreaKillAttribution() {
    }

    public static AreaKillAttribution getInstance() {
        return INSTANCE;
    }

    /**
     * One area-ability hit in progress.
     *
     * @param enchantmentId the ability's enchantment id ({@code chaos}, {@code flash_chaos})
     * @param attackerId    the player who used it
     * @param attackerName  that player's name, as the death message shows it
     * @param item          the item the ability was used with (the plugin's {@code ItemStack}), or null when unknown;
     *                      shown as {@code using <item>} in the death message
     */
    public record Hit(String enchantmentId, UUID attackerId, String attackerName, Object item) {
        public Hit {
            Objects.requireNonNull(enchantmentId, "enchantmentId");
        }

        /** A hit without a known item. */
        public Hit(String enchantmentId, UUID attackerId, String attackerName) {
            this(enchantmentId, attackerId, attackerName, null);
        }
    }

    /** Runs {@code damage} with {@code hit} recorded for {@code victimId}, and forgets it afterwards (also on error). */
    public void during(UUID victimId, Hit hit, Runnable damage) {
        if (victimId == null || hit == null) {
            damage.run();
            return;
        }
        Hit previous = current.put(victimId, hit);
        try {
            damage.run();
        } finally {
            if (previous != null) {
                current.put(victimId, previous);
            } else {
                current.remove(victimId);
            }
        }
    }

    /** The area-ability hit this player is taking at this moment, if any. */
    public Optional<Hit> current(UUID victimId) {
        return victimId == null ? Optional.empty() : Optional.ofNullable(current.get(victimId));
    }
}
