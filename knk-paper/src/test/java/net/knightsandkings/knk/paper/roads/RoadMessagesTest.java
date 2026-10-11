package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import net.knightsandkings.knk.core.exception.ApiException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

class RoadMessagesTest {

    @Test
    void apiRefusalsShowTheApisOwnMessage() {
        ApiException refused = new ApiException("http://api/road-tiles/world/0/0/graph", 400, "Bad Request",
            "{\"error\":\"ValidationFailed\",\"message\":\"Edge 3 geometry ends 4.2 blocks from node n7\"}");
        Throwable wrapped = new CompletionException(new RuntimeException(refused));

        assertEquals("Edge 3 geometry ends 4.2 blocks from node n7", RoadMessages.describeError(wrapped));
        assertEquals("not found", RoadMessages.describeError(new ApiException("u", 404, "Not Found", "")));
        assertTrue(RoadMessages.isNotFound(new CompletionException(new ApiException("u", 404, "Not Found", null))));
        assertFalse(RoadMessages.isNotFound(refused));
        assertEquals("boom", RoadMessages.describeError(new IllegalStateException("boom")));
    }

    @Test
    void clickableCommandsAndUnits() {
        Component command = RoadMessages.command("/knk road show");
        assertNotNull(command.clickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, command.clickEvent().action());
        assertEquals("/knk road show", command.clickEvent().value());
        assertEquals("/knk road goto 10 64 -20", RoadMessages.teleport(10, 64, -20).clickEvent().value());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, RoadMessages.suggest("[edit]", "/knk road survey save ").clickEvent().action());
        assertEquals("46 m", RoadMessages.distance(46.4));
        assertEquals("1.2 km", RoadMessages.distance(1234));
        assertEquals("35%", RoadMessages.percent(0.351));
    }
}
