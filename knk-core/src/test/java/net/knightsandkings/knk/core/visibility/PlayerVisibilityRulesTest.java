package net.knightsandkings.knk.core.visibility;

import net.knightsandkings.knk.core.visibility.PlayerVisibilityRules.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Survival link S3: the ghost reason wins; without a ghost the vanish rule is the pre-S3 one. */
class PlayerVisibilityRulesTest {

    private static final UUID ALLY = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();
    private static final GhostTeam TEAM = new GhostTeam("siege:1:2", Set.of(ALLY)::contains);

    @Test
    void withoutAGhostTheVanishRuleIsTheOldOne() {
        assertEquals(Verdict.VISIBLE, PlayerVisibilityRules.decide(false, false, false));
        assertEquals(Verdict.VISIBLE, PlayerVisibilityRules.decide(false, true, false));
        assertEquals(Verdict.HIDDEN_UNLESS_STAFF, PlayerVisibilityRules.decide(false, false, true));
        assertEquals(Verdict.HIDDEN_UNLESS_STAFF, PlayerVisibilityRules.decide(false, true, true));
        assertEquals(Verdict.VISIBLE, PlayerVisibilityRules.decide(null, OUTSIDER, false));
        assertEquals(Verdict.HIDDEN_UNLESS_STAFF, PlayerVisibilityRules.decide(null, OUTSIDER, true));
    }

    @Test
    void aGhostIsHiddenFromOutsidersWhateverElseApplies() {
        assertEquals(Verdict.HIDDEN, PlayerVisibilityRules.decide(true, false, false));
        assertEquals(Verdict.HIDDEN, PlayerVisibilityRules.decide(true, false, true), "staff vanish never reveals a ghost");
        assertEquals(Verdict.HIDDEN, PlayerVisibilityRules.decide(TEAM, OUTSIDER, false));
        assertEquals(Verdict.HIDDEN, PlayerVisibilityRules.decide(TEAM, null, false));
    }

    @Test
    void teammatesSeeTheGhost_unlessItIsAlsoVanished() {
        assertEquals(Verdict.VISIBLE, PlayerVisibilityRules.decide(true, true, false));
        assertEquals(Verdict.VISIBLE, PlayerVisibilityRules.decide(TEAM, ALLY, false));
        assertEquals(Verdict.HIDDEN_UNLESS_STAFF, PlayerVisibilityRules.decide(TEAM, ALLY, true));
    }

    @Test
    void hiddenFirst() {
        assertFalse(Verdict.VISIBLE.hiddenFirst());
        assertTrue(Verdict.HIDDEN.hiddenFirst());
        assertTrue(Verdict.HIDDEN_UNLESS_STAFF.hiddenFirst());
    }

    @Test
    void ghostTeamsAreEqualByKey() {
        assertEquals(TEAM, new GhostTeam("siege:1:2", id -> false));
        assertNotEquals(TEAM, new GhostTeam("siege:1:3", Set.of(ALLY)::contains));
        assertEquals(TEAM.hashCode(), new GhostTeam("siege:1:2", id -> true).hashCode());
        assertTrue(TEAM.includes(ALLY));
        assertFalse(TEAM.includes(OUTSIDER));
        assertFalse(TEAM.includes(null));
        assertEquals("GhostTeam[siege:1:2]", TEAM.toString());
    }
}
