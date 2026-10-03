package net.knightsandkings.knk.core.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;

/**
 * KNG-34 leaver fix (DESIGN.md §F.6, plan §8 link 4 criterion 5): departed members are appended to the
 * completion payload with their stats and {@code leftAt}; repeated leaves merge; a rejoined member's
 * earlier stats merge into their present result.
 */
class SiegeDepartedMembersTest {

    private static final Instant LEFT = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant LEFT_AGAIN = Instant.parse("2026-10-03T12:05:00Z");

    private final SiegeDepartedMembers departed = new SiegeDepartedMembers();

    private static MemberView view(int team, int kills, int deaths, int highestStreak, int captures) {
        return new MemberView(UUID.randomUUID(), team, kills, deaths, 0, highestStreak, captures, null);
    }

    @Test
    void withoutDeparturesThePresentMembersAreUnchanged() {
        List<ParticipantResult> present = List.of(new ParticipantResult(7, 201, 3, 1, 2, 1));

        assertEquals(present, departed.appendTo(present));
        assertTrue(departed.isEmpty());
    }

    @Test
    void aLeaverIsAppendedWithTheirStatsAndLeftAt() {
        departed.departed(8, view(202, 4, 2, 3, 1), LEFT);
        List<ParticipantResult> present = List.of(new ParticipantResult(7, 201, 3, 1, 2, 1));

        List<ParticipantResult> all = departed.appendTo(present);

        assertEquals(List.of(new ParticipantResult(7, 201, 3, 1, 2, 1),
                new ParticipantResult(8, 202, 4, 2, 3, 1, LEFT)), all);
    }

    @Test
    void repeatedLeavesMergeIntoOneResultWithTheFirstLeftAt() {
        departed.departed(8, view(202, 4, 2, 3, 1), LEFT);
        departed.departed(8, view(202, 1, 1, 1, 0), LEFT_AGAIN);

        List<ParticipantResult> all = departed.appendTo(List.of());

        assertEquals(List.of(new ParticipantResult(8, 202, 5, 3, 3, 1, LEFT)), all);
        assertEquals(1, departed.size());
    }

    @Test
    void aRejoinedMembersEarlierStatsMergeIntoTheirPresentResult() {
        departed.departed(7, view(201, 2, 1, 2, 0), LEFT);
        List<ParticipantResult> present = List.of(new ParticipantResult(7, 201, 1, 1, 1, 1),
                new ParticipantResult(9, 202, 0, 0, 0, 0));

        List<ParticipantResult> all = departed.appendTo(present);

        assertEquals(List.of(new ParticipantResult(7, 201, 3, 2, 2, 1), new ParticipantResult(9, 202, 0, 0, 0, 0)), all,
                "one row per user, no leftAt - the API keeps the left marker it already has");
    }

    @Test
    void aNullViewIsIgnoredAndTheInputListIsNotModified() {
        departed.departed(8, null, LEFT);
        List<ParticipantResult> present = List.of(new ParticipantResult(7, 201, 3, 1, 2, 1));

        assertEquals(present, departed.appendTo(present));
        assertEquals(List.of(), departed.appendTo(null));
    }
}
