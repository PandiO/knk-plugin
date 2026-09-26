package net.knightsandkings.knk.core.siege;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The per-second enchant-book drop roll (DESIGN §9.4, v2 {@code SiegeScenario.spawnEnchantments}):
 * with probability {@code chancePerMille}/1000, and while fewer than {@code maxBooksAlive} siege books
 * lie on the ground, one book drops at a uniformly random point inside a random objective's capture
 * radius, with a random allowed enchantment and a level in {@code [levelMin, levelMax]}.
 * <p>
 * v2 cast the radius to int ({@code nextInt((int) 2.5)}), so books landed within 2 blocks of the
 * centre; this samples the real disc uniformly (radius × √u).
 */
public final class EnchantDropPlanner {
    private EnchantDropPlanner() {}

    /** A drop at the objective's capture point plus ({@code offsetX}, {@code offsetZ}). */
    public record Drop(int objectiveIndex, double offsetX, double offsetZ, String enchantmentKey, int level) {}

    /**
     * @param radii capture radius of each candidate objective (the caller keeps the objectives in the same order)
     */
    public static Optional<Drop> roll(RandomGenerator random, int chancePerMille, int booksAlive, int maxBooksAlive,
                                      List<Double> radii, List<String> enchantmentKeys, int levelMin, int levelMax) {
        if (chancePerMille <= 0 || radii.isEmpty() || enchantmentKeys.isEmpty()) return Optional.empty();
        if (booksAlive >= maxBooksAlive) return Optional.empty();
        if (random.nextInt(1000) >= chancePerMille) return Optional.empty();

        int objective = random.nextInt(radii.size());
        double radius = Math.max(0, radii.get(objective));
        double distance = radius * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble() * 2 * Math.PI;
        String key = enchantmentKeys.get(random.nextInt(enchantmentKeys.size()));
        int lo = Math.max(1, Math.min(levelMin, levelMax));
        int hi = Math.max(lo, levelMax);
        int level = lo + random.nextInt(hi - lo + 1);
        return Optional.of(new Drop(objective, distance * Math.cos(angle), distance * Math.sin(angle), key, level));
    }
}
