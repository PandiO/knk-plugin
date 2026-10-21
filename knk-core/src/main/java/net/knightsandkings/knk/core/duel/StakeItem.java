package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * A reference to a stack in its owner's inventory (DESIGN §4.4 {@code ItemRef}, D3): items don't move
 * during negotiation. The runtime keeps the actual {@code ItemStack} copy under {@code refId} and
 * matches it at lock-in ({@code isSimilar} + amount).
 *
 * @param refId    unique per owner within a negotiation; identifies "the same item" across counters
 * @param material the material name, for messages and the audit summary
 */
public record StakeItem(String refId, String material, int amount) {
    public StakeItem {
        Objects.requireNonNull(refId, "refId");
        Objects.requireNonNull(material, "material");
        if (amount < 1) throw new IllegalArgumentException("amount must be >= 1");
    }
}
