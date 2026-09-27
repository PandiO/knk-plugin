package net.knightsandkings.knk.paper.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

/** KNG-18 Phase 3: Paper's "issued server command" lines for PMs are dropped, others kept. */
class PrivateMessageCommandLogFilterTest {

    private final PrivateMessageCommandLogFilter filter = new PrivateMessageCommandLogFilter();

    private Result filter(org.apache.logging.log4j.message.Message message) {
        return filter.filter(Log4jLogEvent.newBuilder().setLevel(Level.INFO).setMessage(message).build());
    }

    @Test
    void privateMessageCommands_AreDenied() {
        assertEquals(Result.DENY, filter(new SimpleMessage("Alice issued server command: /msg Bob hi")));
        assertEquals(Result.DENY, filter(new ParameterizedMessage("{} issued server command: {}", "Alice", "/r ok")));
        assertEquals(Result.DENY, filter(new SimpleMessage("Alice issued server command: /minecraft:tell Bob hi")));
    }

    @Test
    void everythingElse_IsLeftToTheOtherFilters() {
        assertEquals(Result.NEUTRAL, filter(new SimpleMessage("Alice issued server command: /spawn")));
        assertEquals(Result.NEUTRAL, filter(new SimpleMessage("Alice joined the game")));
        assertEquals(Result.NEUTRAL, filter.filter(null));
    }
}
