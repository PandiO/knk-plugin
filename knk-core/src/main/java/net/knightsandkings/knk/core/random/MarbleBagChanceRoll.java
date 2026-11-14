package net.knightsandkings.knk.core.random;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * A {@link ChanceRoll} that keeps a {@link ChanceBag} per subject and key (PandiO/knk-workspace#476), so each
 * player's own draws even out: a player's Poison and Freeze each have their own bag, and one player's luck never
 * uses up another's.
 * <p>
 * When the chance for a subject and key changes (another enchantment level, new combat values), the bag is replaced
 * by a new one for the new chance. A subject without an id gets an independent roll. Safe to use from several
 * threads when the {@link RandomGenerator} is.
 */
public final class MarbleBagChanceRoll implements ChanceRoll {
    private final ChanceBag.Size size;
    private final RandomGenerator random;
    private final Map<UUID, Map<String, ChanceBag>> bags = new ConcurrentHashMap<>();

    public MarbleBagChanceRoll(ChanceBag.Size size, RandomGenerator random) {
        this.size = Objects.requireNonNull(size, "size must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public boolean roll(UUID subject, String key, double chance) {
        if (Double.isNaN(chance) || chance <= 0.0d) {
            return false;
        }
        if (chance >= 1.0d) {
            return true;
        }
        if (subject == null || key == null) {
            return random.nextDouble() < chance;
        }
        boolean[] hit = new boolean[1];
        // compute() runs one at a time per subject, so two hits of one player never draw from a bag at once.
        bags.compute(subject, (id, own) -> {
            Map<String, ChanceBag> byKey = own != null ? own : new ConcurrentHashMap<>();
            ChanceBag bag = byKey.get(key);
            if (bag == null || Double.compare(bag.chance(), chance) != 0) {
                bag = new ChanceBag(chance, size, random);
                byKey.put(key, bag);
            }
            hit[0] = bag.next();
            return byKey;
        });
        return hit[0];
    }

    /** Drops what is remembered for a subject, such as a player who left. */
    public void forget(UUID subject) {
        if (subject != null) {
            bags.remove(subject);
        }
    }
}
