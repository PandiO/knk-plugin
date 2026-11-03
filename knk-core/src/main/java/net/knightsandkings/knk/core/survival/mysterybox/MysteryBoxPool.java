package net.knightsandkings.knk.core.survival.mysterybox;

import net.knightsandkings.knk.core.survival.ReadinessIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * A Mystery Box pool (S24, S59, §8a): entries with percentage chances for one variant. Validation: non-empty,
 * every entry's kind matches the variant, chances 1..10000 bp each and exactly 10000 bp in total ("exactly 100 %"),
 * references resolve. The pick is a single weighted draw over the chances with the injected random source; an item
 * entry's enchantments are rolled independently afterwards.
 */
public record MysteryBoxPool(MysteryBoxVariant variant, List<MysteryBoxEntry> entries) {

    public MysteryBoxPool {
        if (variant == null) throw new IllegalArgumentException("variant is required");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public int totalBasisPoints() {
        int total = 0;
        for (MysteryBoxEntry entry : entries) total += entry.basisPoints();
        return total;
    }

    /** Structural validation only (no reference checks). */
    public List<ReadinessIssue> validate() {
        return validate(id -> true, id -> true);
    }

    /**
     * @param blueprintExists    whether an item blueprint id resolves
     * @param enchantmentExists  whether an enchantment id resolves
     */
    public List<ReadinessIssue> validate(Predicate<String> blueprintExists, Predicate<String> enchantmentExists) {
        List<ReadinessIssue> issues = new ArrayList<>();
        if (entries.isEmpty()) {
            issues.add(ReadinessIssue.error("box.empty", "The Mystery Box pool has no entries"));
            return List.copyOf(issues);
        }
        for (MysteryBoxEntry entry : entries) {
            if (entry.basisPoints() < 1 || entry.basisPoints() > BasisPoints.WHOLE) {
                issues.add(ReadinessIssue.error("box.chance-range", entry.describe() + ": chance must be between 0.01 % and 100 %"));
            }
            switch (entry) {
                case MysteryBoxEntry.ItemEntry item -> {
                    if (variant != MysteryBoxVariant.ITEMS) {
                        issues.add(ReadinessIssue.error("box.variant", entry.describe() + " does not belong in an enchanted-book pool"));
                    }
                    if (!blueprintExists.test(item.blueprintId())) {
                        issues.add(ReadinessIssue.error("box.unknown-blueprint", "Unknown item blueprint " + item.blueprintId()));
                    }
                    Set<String> seen = new HashSet<>();
                    for (EnchantmentChance chance : item.enchantments()) {
                        if (!seen.add(chance.enchantmentId())) {
                            issues.add(ReadinessIssue.error("box.duplicate-enchantment",
                                entry.describe() + ": enchantment " + chance.enchantmentId() + " listed twice"));
                        }
                        if (!enchantmentExists.test(chance.enchantmentId())) {
                            issues.add(ReadinessIssue.error("box.unknown-enchantment", "Unknown enchantment " + chance.enchantmentId()));
                        }
                    }
                }
                case MysteryBoxEntry.BookEntry book -> {
                    if (variant != MysteryBoxVariant.ENCHANTED_BOOKS) {
                        issues.add(ReadinessIssue.error("box.variant", entry.describe() + " does not belong in an item pool"));
                    }
                    if (!enchantmentExists.test(book.enchantmentId())) {
                        issues.add(ReadinessIssue.error("box.unknown-enchantment", "Unknown enchantment " + book.enchantmentId()));
                    }
                }
            }
        }
        int total = totalBasisPoints();
        if (total != BasisPoints.WHOLE) {
            issues.add(ReadinessIssue.error("box.total",
                String.format("Pool chances add up to %.2f %%, not exactly 100 %%", BasisPoints.toPercent(total))));
        }
        return List.copyOf(issues);
    }

    public boolean isValid() {
        return validate().stream().noneMatch(ReadinessIssue::isError);
    }

    /**
     * One pick with the real odds. The pool must be valid (total exactly 100 %).
     *
     * @throws IllegalStateException when the pool is not valid
     */
    public MysteryBoxRoll pick(RandomGenerator random) {
        if (entries.isEmpty() || totalBasisPoints() != BasisPoints.WHOLE) {
            throw new IllegalStateException("Mystery Box pool is not valid: " + validate());
        }
        int roll = random.nextInt(BasisPoints.WHOLE);
        MysteryBoxEntry picked = entries.get(entries.size() - 1);
        for (MysteryBoxEntry entry : entries) {
            roll -= entry.basisPoints();
            if (roll < 0) {
                picked = entry;
                break;
            }
        }
        List<EnchantmentChance> rolled = new ArrayList<>();
        if (picked instanceof MysteryBoxEntry.ItemEntry item) {
            for (EnchantmentChance chance : item.enchantments()) {
                if (random.nextInt(BasisPoints.WHOLE) < chance.basisPoints()) rolled.add(chance);
            }
        }
        return new MysteryBoxRoll(picked, rolled);
    }
}
