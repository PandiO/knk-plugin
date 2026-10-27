package net.knightsandkings.knk.paper.user;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#170 round 2: who froze a player, in memory, for the "Frozen by <staff>" boss bar. */
class AdminFreezeManagerFreezerTest {

    private final AdminFreezeManager freezes = new AdminFreezeManager();
    private final UUID uuid = UUID.randomUUID();

    @Test
    void theFreezerIsKnownBeforeTheHookRuns_AndGoneAfterAnUnfreeze() {
        List<String> seenByHook = new ArrayList<>();
        freezes.setFreezeHook((id, frozen) -> seenByHook.add(frozen + ":" + freezes.frozenBy(id) + ":" + freezes.reasonFor(id)));

        freezes.freeze(uuid, "griefing", "Admin");
        assertEquals("Admin", freezes.frozenBy(uuid));
        freezes.unfreeze(uuid);
        assertNull(freezes.frozenBy(uuid));

        assertEquals(List.of("true:Admin:griefing", "false:null:null"), seenByHook);
    }

    @Test
    void consoleOrVanishedStaff_TheOldTwoArgumentFreeze_HaveNoFreezer() {
        freezes.freeze(uuid, "spam", "Admin");
        freezes.freeze(uuid, "spam", null); // re-frozen by the console
        assertNull(freezes.frozenBy(uuid));
        freezes.freeze(uuid, "spam");
        assertNull(freezes.frozenBy(uuid));
    }

    @Test
    void aQuitKeepsTheFreezerForTheNextJoin() {
        freezes.freeze(uuid, "griefing", "Admin");
        freezes.forget(uuid);
        assertEquals("Admin", freezes.frozenBy(uuid));
    }

    @Test
    void anOfflineFreezeRemembersTheFreezer_AnOfflineUnfreezeForgetsIt() {
        freezes.rememberFreezer(uuid, "griefing", "Admin");
        assertEquals("Admin", freezes.frozenBy(uuid));
        freezes.rememberFreezer(uuid, null, null);
        assertNull(freezes.frozenBy(uuid));
    }
}
