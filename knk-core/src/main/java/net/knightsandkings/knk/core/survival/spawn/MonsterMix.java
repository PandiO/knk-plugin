package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.ReadinessIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The monster mix of a scenario or special profile (S42, S56): a round's enemies are drawn by weight from the
 * selected types unlocked in that round. Deterministic given the injected random source.
 */
public final class MonsterMix {
    private final List<MonsterType> types;

    public MonsterMix(List<MonsterType> types) {
        this.types = List.copyOf(types);
    }

    public static MonsterMix of(MonsterType... types) {
        return new MonsterMix(List.of(types));
    }

    public List<MonsterType> types() {
        return types;
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    /** The types that can appear in {@code round}. */
    public List<MonsterType> eligible(int round) {
        List<MonsterType> eligible = new ArrayList<>();
        for (MonsterType type : types) {
            if (type.unlockedIn(round)) eligible.add(type);
        }
        return List.copyOf(eligible);
    }

    /** One type for one enemy of {@code round}; empty when no type is unlocked yet. */
    public Optional<MonsterType> draw(int round, RandomGenerator random) {
        return WeightedPick.pick(eligible(round), MonsterType::weight, random);
    }

    /** Readiness (§7): at least one selected type, and one of them available in round 1; no duplicate ids. */
    public List<ReadinessIssue> readiness() {
        List<ReadinessIssue> issues = new ArrayList<>();
        if (types.isEmpty()) {
            issues.add(ReadinessIssue.error("mix.empty", "Select at least one monster type"));
            return issues;
        }
        if (eligible(1).isEmpty()) {
            issues.add(ReadinessIssue.error("mix.none-in-round-1", "No selected monster type is unlocked in round 1"));
        }
        Set<String> seen = new HashSet<>();
        for (MonsterType type : types) {
            if (!seen.add(type.id())) {
                issues.add(ReadinessIssue.error("mix.duplicate", "Monster type selected twice: " + type.id()));
            }
        }
        return List.copyOf(issues);
    }
}
