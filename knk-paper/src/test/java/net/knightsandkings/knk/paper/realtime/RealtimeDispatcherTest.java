package net.knightsandkings.knk.paper.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#178: event type to handler, forward compatible (DESIGN §4.2, §4.4). */
class RealtimeDispatcherTest {

    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();

    RealtimeDispatcherTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private static RealtimeEnvelope envelope(String type, int v) {
        return RealtimeEnvelope.parse("{\"v\":" + v + ",\"seq\":1,\"type\":\"" + type + "\",\"payload\":{\"id\":5}}", new ObjectMapper());
    }

    @Test
    void routesByTypeOnTheHandlersExecutor() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(logger);
        List<Runnable> queued = new ArrayList<>();
        List<String> handled = new ArrayList<>();
        dispatcher.register("UserChanged", 1, queued::add, e -> handled.add(e.type() + ":" + e.payload().get("id").asInt()));

        assertTrue(dispatcher.dispatch(envelope("UserChanged", 1)));
        assertTrue(handled.isEmpty(), "runs on its executor, not inline");
        queued.forEach(Runnable::run);

        assertEquals(List.of("UserChanged:5"), handled);
        assertEquals(Set.of("UserChanged"), dispatcher.types());
    }

    @Test
    void unknownTypesAndNewerVersionsAreIgnoredAndLoggedOnce() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(logger);
        List<String> handled = new ArrayList<>();
        dispatcher.register("UserChanged", 1, Runnable::run, e -> handled.add("v" + e.v()));

        assertFalse(dispatcher.dispatch(envelope("SomethingNew", 1)));
        assertFalse(dispatcher.dispatch(envelope("SomethingNew", 1)));
        assertFalse(dispatcher.dispatch(envelope("UserChanged", 2)));
        assertFalse(dispatcher.dispatch(envelope("UserChanged", 2)));
        assertTrue(dispatcher.dispatch(envelope("UserChanged", 1)));

        assertEquals(List.of("v1"), handled);
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void aFailingHandlerIsLoggedNotThrown() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(logger);
        dispatcher.register("Boom", 1, Runnable::run, e -> {
            throw new IllegalStateException("handler bug");
        });

        assertTrue(dispatcher.dispatch(envelope("Boom", 1)));
        assertEquals(1, warnings.size());
    }
}
