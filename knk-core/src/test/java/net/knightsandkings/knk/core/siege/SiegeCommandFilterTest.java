package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: the in-match command filter (DESIGN §6.9, fixes N10). */
class SiegeCommandFilterTest {

    private static final List<String> LEGACY = List.of("/siege", "/msg", "/r", "/staffchat", "/menu");

    @Test
    void allowsListedCommandsWithArgumentsAndAnyCase() {
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob hello", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/MSG Bob", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/r ok", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/menu", LEGACY));
    }

    @Test
    void blocksEverythingElseIncludingPrefixLookalikes() {
        assertFalse(SiegeCommandFilter.isAllowed("/spawn", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/msgall hi", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/kit get Archer", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/reply hi", LEGACY), "without a resolver aliases aren't resolved");
    }

    @Test
    void namespacedFormsAreNormalisedSoTheyCantBypassTheList() {
        assertFalse(SiegeCommandFilter.isAllowed("/minecraft:tp 0 0 0", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/knightsandkings:msg Bob", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/msg x", List.of("knightsandkings:msg")));
    }

    @Test
    void siegeIsAlwaysAllowedSoMembersCanLeave() {
        assertTrue(SiegeCommandFilter.isAllowed("/siege leave", List.of()));
        assertTrue(SiegeCommandFilter.isAllowed("/knightsandkings:siege vote 1", null));
        assertTrue(SiegeCommandFilter.isAllowed("/siegemenu", List.of()));
        assertTrue(SiegeCommandFilter.isAllowed("/SGM", null));
        assertFalse(SiegeCommandFilter.isAllowed("/msg x", null));
    }

    @Test
    void labelStripsSlashNamespaceAndArguments() {
        assertEquals("siege", SiegeCommandFilter.label("/KnK:Siege join cinix"));
        assertEquals("msg", SiegeCommandFilter.label("msg"));
        assertEquals("", SiegeCommandFilter.label("/"));
        assertEquals("", SiegeCommandFilter.label(null));
    }

    // ---- Alias resolution (private-messages DESIGN §4 D9, IMPLEMENTATION_PLAN Phase F) ----

    /** What the plugin's command map answers: every /msg and /reply alias, plus two other commands. */
    private static final Map<String, String> COMMANDS = Map.ofEntries(
            Map.entry("msg", "msg"), Map.entry("message", "msg"), Map.entry("tell", "msg"),
            Map.entry("whisper", "msg"), Map.entry("w", "msg"), Map.entry("m", "msg"), Map.entry("pm", "msg"),
            Map.entry("reply", "reply"), Map.entry("r", "reply"),
            Map.entry("staffchat", "staffchat"), Map.entry("sc", "staffchat"),
            Map.entry("spawn", "spawn"), Map.entry("teammsg", "teammsg"), Map.entry("tm", "teammsg"),
            Map.entry("siegemenu", "siegemenu"));
    private static final UnaryOperator<String> RESOLVER = label -> COMMANDS.getOrDefault(label, label);

    @Test
    void everyAliasOfAnAllowedPrivateMessageCommandIsAllowed() {
        for (String spelling : List.of("/msg", "/message", "/tell", "/whisper", "/w", "/m", "/pm")) {
            assertTrue(SiegeCommandFilter.isAllowed(spelling + " Bob hi", LEGACY, RESOLVER), spelling);
        }
        assertTrue(SiegeCommandFilter.isAllowed("/reply thanks", LEGACY, RESOLVER), "/r is listed, /reply is its command");
        assertTrue(SiegeCommandFilter.isAllowed("/r thanks", LEGACY, RESOLVER));
        assertTrue(SiegeCommandFilter.isAllowed("/sc hi", LEGACY, RESOLVER), "/staffchat is listed");
    }

    @Test
    void namespacedFormsResolveToTheSameCommand() {
        for (String spelling : List.of("/minecraft:msg", "/minecraft:tell", "/minecraft:w",
                "/knightsandkings:msg", "/knightsandkings:tell", "/KnightsAndKings:Reply")) {
            assertTrue(SiegeCommandFilter.isAllowed(spelling + " Bob hi", LEGACY, RESOLVER), spelling);
        }
        assertFalse(SiegeCommandFilter.isAllowed("/minecraft:teammsg hi", LEGACY, RESOLVER));
        assertFalse(SiegeCommandFilter.isAllowed("/minecraft:tm hi", LEGACY, RESOLVER));
    }

    @Test
    void anAliasOnTheListAllowsTheWholeCommand() {
        List<String> list = List.of("/w", "/reply");
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob hi", list, RESOLVER));
        assertTrue(SiegeCommandFilter.isAllowed("/tell Bob hi", list, RESOLVER));
        assertTrue(SiegeCommandFilter.isAllowed("/r ok", list, RESOLVER));
    }

    @Test
    void resolvingDoesNotOpenAnythingElse() {
        assertFalse(SiegeCommandFilter.isAllowed("/spawn", LEGACY, RESOLVER));
        assertFalse(SiegeCommandFilter.isAllowed("/tm hi", LEGACY, RESOLVER));
        assertFalse(SiegeCommandFilter.isAllowed("/msgall hi", LEGACY, RESOLVER), "unknown labels only match themselves");
        assertFalse(SiegeCommandFilter.isAllowed("/tell Bob", null, RESOLVER));
        assertFalse(SiegeCommandFilter.isAllowed("/tell Bob", List.of("", "/"), RESOLVER), "blank entries match nothing");
        assertTrue(SiegeCommandFilter.isAllowed("/siege leave", List.of(), RESOLVER));
        assertTrue(SiegeCommandFilter.isAllowed("/sgm", List.of(), label -> label.equals("sgm") ? "siegemenu" : label));
    }

    @Test
    void aResolverWithoutAnAnswerFallsBackToTheLabel() {
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob", LEGACY, label -> null));
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob", LEGACY, label -> ""));
        assertFalse(SiegeCommandFilter.isAllowed("/tell Bob", LEGACY, label -> null));
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob", LEGACY, null));
    }
}
