package net.knightsandkings.knk.paper.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.realtime.GateCommandEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler;
import net.knightsandkings.knk.core.realtime.RecentActionIds;
import net.knightsandkings.knk.paper.commands.GateCommandSupport;
import net.knightsandkings.knk.paper.gates.GateTargeting;

/**
 * Overview map L7 (PandiO/knk-workspace#741, spec § 6.2.5–6.2.6, § 7.1.3, A18/A19 plugin side): {@code GateCommand}
 * on the game server, wired as in {@code KnKPlugin.registerGateCommandSync}: the handler runs on the "main thread"
 * (inline, counted) with the real {@link GateManager} and the real {@code /gate repair} ({@link GateCommandSupport},
 * persisting through a mocked {@link GateDoorsApi}), and answers {@code GateCommandResult} once per command id.
 */
class GateCommandRealtimeSyncTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<LogRecord> logs = new ArrayList<>();
    private final List<Object[]> sent = new ArrayList<>();
    private final Set<Integer> locked = new HashSet<>();
    private final GateDoorsApi doorsApi = mock(GateDoorsApi.class);
    private GateManager gates;
    private RealtimeDispatcher dispatcher;
    private int mainThreadTasks;
    private boolean hubConnected = true;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        when(doorsApi.updateHealth(anyInt(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        when(doorsApi.updateState(anyInt(), anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));
        gates = new GateManager();
        gates.cacheStructure(new CachedGateStructure(12, "North Gate"));
        gates.cacheGate(new CachedGateDoor(21, 12, "West", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 1, 1, 1, 500.0, 500.0, true, false, true, 90, "north"));
        GateCommandSupport support = new GateCommandSupport(gates, null, doorsApi, new GateTargeting(gates));
        GateCommandHandler handler = new GateCommandHandler(gates, locked::contains, support::repair);
        GateCommandRealtimeSync sync = new GateCommandRealtimeSync(runnable -> {
            mainThreadTasks++;
            runnable.run();
        }, handler, (method, body) -> {
            sent.add(new Object[] { method, body });
            return hubConnected;
        }, new RecentActionIds(), logger);
        dispatcher = new RealtimeDispatcher(logger);
        sync.register(dispatcher);
    }

    private void send(String payload) {
        assertTrue(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"GateCommand\","
            + "\"actor\":{\"userId\":7,\"source\":\"web\"},\"payload\":" + payload + "}", mapper)));
    }

    private static String command(String id, String action, Integer door) {
        return "{\"commandId\":\"" + id + "\",\"gateId\":12,\"doorId\":" + door + ",\"action\":\"" + action
            + "\",\"staffUserId\":7,\"staffUsername\":\"GateKeeper\"}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyResult() {
        assertEquals(1, sent.size());
        assertEquals(GateCommandEvents.GATE_COMMAND_RESULT, sent.get(0)[0]);
        return (Map<String, Object>) sent.get(0)[1];
    }

    @Test
    void open_RunsOnTheMainThread_AnimatesTheDoor_AndAnswersDone() {
        send(command("c1", "open", 21));

        assertEquals(1, mainThreadTasks);
        assertEquals(AnimationState.OPENING, gates.getGate(21).getCurrentState());
        Map<String, Object> result = onlyResult();
        assertEquals("c1", result.get("commandId"));
        assertEquals("done", result.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> door = ((List<Map<String, Object>>) result.get("doors")).get(0);
        assertEquals(21, door.get("doorId"));
        assertEquals("opening", door.get("state"));
        assertEquals("started", door.get("outcome"));
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("by GateKeeper: done")));
    }

    @Test
    void repair_PersistsHealthAndState_LikeGateRepair() {
        CachedGateDoor west = gates.getGate(21);
        west.setHealthCurrent(10);
        west.setIsDestroyed(true);

        send(command("c2", "repair", 21));

        assertEquals(500.0, west.getHealthCurrent());
        assertFalse(west.isDestroyed());
        verify(doorsApi).updateHealth(21, 500.0);
        verify(doorsApi).updateState(eq(21), anyString(), eq(false));
        assertEquals("done", onlyResult().get("result"));
    }

    @Test
    void underASiegeLockdown_AnswersSiege_AndTheDoorStays() {
        locked.add(12);

        send(command("c3", "open", null));

        assertEquals(AnimationState.CLOSED, gates.getGate(21).getCurrentState());
        assertEquals("siege", onlyResult().get("result"));
        assertEquals("Gate is in a running siege.", onlyResult().get("message"));
    }

    @Test
    void aForeignDoor_AnswersRefused() {
        send("{\"commandId\":\"c4\",\"gateId\":12,\"doorId\":99,\"action\":\"open\"}");
        assertEquals("refused", onlyResult().get("result"));
    }

    @Test
    void theSameCommandId_RunsAndAnswersOnce() {
        send(command("c5", "toggle", 21));
        send(command("c5", "toggle", 21));

        assertEquals(1, mainThreadTasks);
        assertEquals(1, sent.size());
        assertEquals(AnimationState.OPENING, gates.getGate(21).getCurrentState());
    }

    @Test
    void aMalformedCommand_IsIgnored_NothingRunsNoAnswer() {
        send("{\"gateId\":12,\"action\":\"open\"}");

        assertEquals(0, mainThreadTasks);
        assertTrue(sent.isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("ignored")));
    }

    @Test
    void anUnknownAction_AnswersRefused() {
        send(command("c6", "explode", 21));
        assertEquals("refused", onlyResult().get("result"));
        assertEquals(AnimationState.CLOSED, gates.getGate(21).getCurrentState());
    }

    @Test
    void hubDown_TheCommandStillRuns_AndTheMissingAnswerIsLogged() {
        hubConnected = false;

        send(command("c7", "open", 21));

        assertEquals(AnimationState.OPENING, gates.getGate(21).getCurrentState());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("not sent")));
    }

    @Test
    void theBody_HasEveryFieldTheApiReads() {
        GateCommandHandler.Result result = new GateCommandHandler.Result(GateCommandHandler.Outcome.REFUSED, "no", List.of(
            new GateCommandHandler.DoorResult(21, "West", "closed", 0, 500, true, GateCommandHandler.DoorOutcome.SKIPPED, "destroyed")));

        Map<String, Object> body = GateCommandRealtimeSync.body("c8", result);

        assertEquals(List.of("commandId", "result", "message", "doors"), List.copyOf(body.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> door = ((List<Map<String, Object>>) body.get("doors")).get(0);
        assertEquals(List.of("doorId", "state", "healthCurrent", "healthMax", "destroyed", "outcome", "reason"),
            List.copyOf(door.keySet()));
        assertEquals("skipped", door.get("outcome"));
        assertNull(body.get("nothing"));
    }
}
