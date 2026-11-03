package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveEntry.Explicit;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveEntry.Recurring;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S14, S53: recurring and explicit entries together; explicit beats recurring; priorities. */
class SpecialWaveScheduleTest {

    @Test
    void recurringEveryFifthWave() {
        Recurring every5 = Recurring.every(5, "wolves", 1);
        assertFalse(every5.matches(1));
        assertTrue(every5.matches(5));
        assertTrue(every5.matches(10));
        assertFalse(every5.matches(12));
        Recurring from7every3 = new Recurring(3, 7, "boss", 1);
        assertTrue(from7every3.matches(7) && from7every3.matches(10) && !from7every3.matches(4));
    }

    @Test
    void explicitBeatsRecurringAndLowestPriorityWinsAmongRecurring() {
        SpecialWaveSchedule schedule = new SpecialWaveSchedule(List.of(
            Recurring.every(5, "wolves", 2),
            Recurring.every(10, "giants", 1),
            new Explicit(10, "boss")));
        assertEquals(Optional.empty(), schedule.profileIdFor(3));
        assertEquals(Optional.of("wolves"), schedule.profileIdFor(5));
        assertEquals(Optional.of("boss"), schedule.profileIdFor(10), "explicit beats both recurring");
        assertEquals(Optional.of("giants"), schedule.profileIdFor(20), "priority 1 beats 2");
    }

    @Test
    void conflictDetectionUsesTheCommonWaves() {
        assertTrue(Recurring.every(5, "a", 1).conflictsWith(Recurring.every(10, "b", 1)));
        assertTrue(new Recurring(4, 2, "a", 1).conflictsWith(new Recurring(6, 8, "b", 1)), "waves 14, 26…");
        assertFalse(new Recurring(4, 1, "a", 1).conflictsWith(new Recurring(4, 2, "b", 1)), "odd vs even offsets, same interval");
        assertFalse(new Recurring(2, 1, "a", 1).conflictsWith(new Recurring(4, 2, "b", 1)), "1,3,5… vs 2,6,10…");
    }

    @Test
    void readinessRequiresUniquePrioritiesOnConflictAndKnownProfiles() {
        SpecialWaveSchedule bad = new SpecialWaveSchedule(List.of(
            Recurring.every(5, "wolves", 1),
            Recurring.every(10, "giants", 1),
            new Explicit(7, "boss"),
            new Explicit(7, "other"),
            new Explicit(8, "missing")));
        List<String> codes = bad.readiness(Set.of("wolves", "giants", "boss", "other")).stream().map(ReadinessIssue::code).toList();
        assertTrue(codes.contains("special.recurring-priority"));
        assertTrue(codes.contains("special.explicit-conflict"));
        assertTrue(codes.contains("special.unknown-profile"));

        SpecialWaveSchedule good = new SpecialWaveSchedule(List.of(
            Recurring.every(5, "wolves", 2),
            Recurring.every(10, "giants", 1),
            new Recurring(4, 2, "even", 3),
            new Explicit(7, "boss"),
            new Explicit(7, "boss")));
        assertTrue(good.readiness(Set.of("wolves", "giants", "boss", "even")).isEmpty());
        assertTrue(SpecialWaveSchedule.none().readiness(Set.of()).isEmpty());
    }
}
