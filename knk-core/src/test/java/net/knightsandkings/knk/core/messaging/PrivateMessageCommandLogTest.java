package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** KNG-18 Phase 3: which Paper command-log lines the PM filter drops. */
class PrivateMessageCommandLogTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Alice issued server command: /msg Bob hi",
        "Alice issued server command: /MSG Bob hi",
        "Alice issued server command: /tell Bob hi",
        "Alice issued server command: /w Bob hi",
        "Alice issued server command: /whisper Bob hi",
        "Alice issued server command: /m Bob hi",
        "Alice issued server command: /pm Bob hi",
        "Alice issued server command: /message Bob hi",
        "Alice issued server command: /r thanks",
        "Alice issued server command: /reply thanks",
        "Alice issued server command: /minecraft:tell Bob hi",
        "Alice issued server command: /knightsandkings:msg Bob hi",
        "Alice issued server command: /msg",
    })
    void privateMessageLines_AreMatched(String line) {
        assertTrue(PrivateMessageCommandLog.isPrivateMessageCommandLine(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Alice issued server command: /spawn",
        "Alice issued server command: /msgs",
        "Alice issued server command: /ignore Bob",
        "Alice issued server command: /socialspy off",
        "Alice issued server command: /staffchat hi",
        "Alice lost connection: Disconnected",
        "<Alice> /msg Bob hi",
        "",
    })
    void otherLines_AreKept(String line) {
        assertFalse(PrivateMessageCommandLog.isPrivateMessageCommandLine(line));
    }
}
