package net.knightsandkings.knk.core.survival.mysterybox;

/**
 * An enchantment with its independent chance on an item entry (§8a "optional enchantments per entry with their own
 * chances"): each is rolled on its own, so the chances of one entry need not add up to anything.
 *
 * @param enchantmentId the enchantment's id (opaque to the core)
 * @param level         the level
 * @param basisPoints   the chance in basis points, 1..10000
 */
public record EnchantmentChance(String enchantmentId, int level, int basisPoints) {

    public EnchantmentChance {
        if (enchantmentId == null || enchantmentId.isBlank()) throw new IllegalArgumentException("enchantmentId is required");
        if (level < 1) throw new IllegalArgumentException("level must be >= 1");
        if (basisPoints < 1 || basisPoints > BasisPoints.WHOLE) throw new IllegalArgumentException("basisPoints must be 1..10000");
    }
}
