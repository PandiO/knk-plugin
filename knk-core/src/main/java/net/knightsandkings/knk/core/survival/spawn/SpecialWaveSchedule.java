package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.ReadinessIssue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The special-wave schedule (S14, S53): recurring interval entries AND explicit wave-number entries together.
 * Selection for a wave: the explicit entry if one exists, else the matching recurring entry with the best
 * (lowest) priority. Readiness fails on two explicit entries for one wave with different profiles, on conflicting
 * recurring entries without unique priorities, and on unknown profile references.
 */
public final class SpecialWaveSchedule {
    private final List<SpecialWaveEntry> entries;

    public SpecialWaveSchedule(List<SpecialWaveEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static SpecialWaveSchedule none() {
        return new SpecialWaveSchedule(List.of());
    }

    public List<SpecialWaveEntry> entries() {
        return entries;
    }

    /** The profile id that replaces (or, if the profile is additive, extends) the normal wave {@code wave}. */
    public Optional<String> profileIdFor(int wave) {
        for (SpecialWaveEntry entry : entries) {
            if (entry instanceof SpecialWaveEntry.Explicit explicit && explicit.matches(wave)) {
                return Optional.of(explicit.profileId());
            }
        }
        return entries.stream()
            .filter(e -> e instanceof SpecialWaveEntry.Recurring && e.matches(wave))
            .map(e -> (SpecialWaveEntry.Recurring) e)
            .min(Comparator.comparingInt(SpecialWaveEntry.Recurring::priority))
            .map(SpecialWaveEntry.Recurring::profileId);
    }

    /** @param knownProfileIds the ids of the configured special profiles */
    public List<ReadinessIssue> readiness(Set<String> knownProfileIds) {
        List<ReadinessIssue> issues = new ArrayList<>();
        Map<Integer, String> explicitByWave = new HashMap<>();
        List<SpecialWaveEntry.Recurring> recurring = new ArrayList<>();
        for (SpecialWaveEntry entry : entries) {
            if (!knownProfileIds.contains(entry.profileId())) {
                issues.add(ReadinessIssue.error("special.unknown-profile", "Special-wave entry references unknown profile " + entry.profileId()));
            }
            if (entry instanceof SpecialWaveEntry.Explicit explicit) {
                String previous = explicitByWave.putIfAbsent(explicit.wave(), explicit.profileId());
                if (previous != null && !previous.equals(explicit.profileId())) {
                    issues.add(ReadinessIssue.error("special.explicit-conflict",
                        "Wave " + explicit.wave() + " has two explicit special profiles: " + previous + " and " + explicit.profileId()));
                }
            } else if (entry instanceof SpecialWaveEntry.Recurring r) {
                recurring.add(r);
            }
        }
        for (int i = 0; i < recurring.size(); i++) {
            for (int j = i + 1; j < recurring.size(); j++) {
                SpecialWaveEntry.Recurring a = recurring.get(i);
                SpecialWaveEntry.Recurring b = recurring.get(j);
                if (a.conflictsWith(b) && a.priority() == b.priority()) {
                    issues.add(ReadinessIssue.error("special.recurring-priority",
                        "Recurring special waves \"every " + a.interval() + " from " + a.startWave() + "\" (" + a.profileId()
                            + ") and \"every " + b.interval() + " from " + b.startWave() + "\" (" + b.profileId()
                            + ") can hit the same wave and share priority " + a.priority()));
                }
            }
        }
        return List.copyOf(issues);
    }
}
