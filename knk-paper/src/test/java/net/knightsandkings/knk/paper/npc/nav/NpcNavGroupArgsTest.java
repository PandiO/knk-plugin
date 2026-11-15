package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.group.Formation;

/**
 * The words of {@code /knk npc nav group <n> [formation] [profile] [finish-trip|follow-leader]} (implementation plan
 * 4b.3 item 8): any order, each at most once.
 */
class NpcNavGroupArgsTest {

    private static NpcNavigationModule.GroupArgs parse(String... words) {
        String[] args = new String[words.length + 3];
        args[0] = "nav";
        args[1] = "group";
        args[2] = "20";
        System.arraycopy(words, 0, args, 3, words.length);
        return NpcNavigationModule.GroupArgs.parse(args, 3);
    }

    @Test
    void noWordsLeaveEverythingToTheSettings() {
        assertEquals(new NpcNavigationModule.GroupArgs(null, null, null, null), parse());
    }

    @Test
    void theFormationTheProfileAndTheToggle() {
        assertEquals(new NpcNavigationModule.GroupArgs(Formation.Type.RANKS, null, true, null), parse("ranks", "finish-trip"));
        assertEquals(new NpcNavigationModule.GroupArgs(Formation.Type.CLUSTER, "guards", false, null),
            parse("cluster", "guards", "follow-leader"));
        assertEquals(new NpcNavigationModule.GroupArgs(null, "guards", null, null), parse("guards"),
            "no formation given: the word is the profile");
        assertEquals(new NpcNavigationModule.GroupArgs(null, null, true, null), parse("FINISH-TRIP"));
        assertEquals(new NpcNavigationModule.GroupArgs(Formation.Type.ESCORT_RING, null, false, null),
            parse("follow-leader", "escort-ring"), "any order");
    }

    @Test
    void aWordTwiceOrTooManyIsAnError() {
        assertNotNull(parse("finish-trip", "follow-leader").error());
        assertNotNull(parse("ranks", "guards", "other").error());
        assertNull(parse("ranks", "guards").error());
    }
}
