package net.knightsandkings.knk.paper.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.WorldTaskEvents;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.WorldTaskLeases;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#181 (WT2, DESIGN §8.3–§8.4): what the world-task events do on the game server. Every "main thread"
 * hop runs inline; players are Mockito mocks (no Location involved); the clock and the scheduler are manual.
 */
class WorldTaskRealtimeSyncTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Player> online = new HashMap<>();
    private final WorldTaskHandlerRegistry registry = mock(WorldTaskHandlerRegistry.class);
    private final WorldTasksApi api = mock(WorldTasksApi.class);
    private final WorldTaskLeases leases = new WorldTaskLeases(mock(WorldTasksApi.class), "survival-1");
    private final List<Map<String, Object>> answers = new ArrayList<>();
    private final List<WorldTaskDto> started = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private int headlessPolls;
    private Instant now = Instant.parse("2026-11-02T12:00:00Z");
    private boolean hubUp = true;
    private Player steve;
    private RealtimeDispatcher dispatcher;
    private WorldTaskRealtimeSync sync;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(STEVE);
        when(steve.getName()).thenReturn("Steve");
        when(steve.isOnline()).thenReturn(true);
        when(steve.hasPermission("knk.tasks")).thenReturn(true);
        online.put(STEVE, steve);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        sync = new WorldTaskRealtimeSync(Runnable::run, online::get, registry, api, leases, "survival-1",
            (method, body) -> {
                assertEquals(WorldTaskEvents.WORLD_TASK_OFFER_RESULT, method);
                answers.add((Map<String, Object>) body);
                return hubUp;
            },
            () -> headlessPolls++,
            (player, claimed) -> started.add(claimed),
            (delay, task) -> {
                delays.add(delay);
                scheduled.add(task);
            },
            clock, Logger.getAnonymousLogger());
        dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        sync.register(dispatcher);
    }

    private void send(String type, String payload) {
        assertTrue(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":2,\"type\":\"" + type + "\",\"payload\":"
            + payload + "}", mapper)));
    }

    private static String offer(int taskId, String mode) {
        return "{\"taskId\":" + taskId + ",\"uuid\":\"" + STEVE + "\",\"taskType\":\"LocationSelection\",\"fieldName\":\"Location\","
            + "\"label\":\"Town › Location\",\"mode\":\"" + mode + "\",\"fromUsername\":\"Alice\",\"timeoutSeconds\":120}";
    }

    private static WorldTaskDto claimed(int id) {
        return new WorldTaskDto(id, 1, "Town", 0, "Location", "LocationSelection", "InProgress", "ABC123", "survival-1", "Steve",
            null, null, "{}", null, null, null);
    }

    private String lastResult() {
        return (String) answers.get(answers.size() - 1).get("result");
    }

    @Test
    void registersEveryWt2Type_AndTheAcceptDeclineResponder() {
        assertTrue(dispatcher.types().containsAll(WorldTaskEvents.TYPES));
        verify(registry).setOfferResponder(sync);
    }

    @Test
    void autoClaim_ClaimsWithTheOfferMode_AndStartsTheHandler() {
        when(api.claim(eq(7), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.completedFuture(claimed(7)));

        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(7, "auto-claim"));

        ArgumentCaptor<ClaimTaskDto> claim = ArgumentCaptor.forClass(ClaimTaskDto.class);
        verify(api).claim(eq(7), claim.capture());
        assertEquals(ClaimTaskDto.MODE_OFFER, claim.getValue().claimMode());
        assertEquals(STEVE.toString(), claim.getValue().uuid());
        assertEquals("survival-1", claim.getValue().serverId());
        assertEquals(null, claim.getValue().taskCode());
        assertEquals(List.of(7), started.stream().map(WorldTaskDto::id).toList());
        assertTrue(answers.isEmpty()); // the claim itself is the "accepted"
    }

    @Test
    void autoClaim_RefusedClaim_TellsThePlayer_AndStartsNothing() {
        when(api.claim(eq(8), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.failedFuture(
            new ApiException("u", 409, "Request failed", "{\"code\":\"WorldTaskConflict\",\"message\":\"Task 8 is already claimed by Alex.\"}")));

        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(8, "auto-claim"));

        assertTrue(started.isEmpty());
        verify(steve, atLeastOnce()).sendMessage(org.mockito.ArgumentMatchers.contains("already claimed by Alex"));
    }

    @Test
    void offerToSomeoneNotHere_WithoutKnkTasks_OrBusy_IsAnsweredNotClaimed() {
        online.clear();
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(1, "auto-claim"));
        assertEquals("offline", lastResult());

        online.put(STEVE, steve);
        when(steve.hasPermission("knk.tasks")).thenReturn(false);
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(2, "auto-claim"));
        assertEquals("no-permission", lastResult());

        when(steve.hasPermission("knk.tasks")).thenReturn(true);
        when(registry.isHandling(STEVE)).thenReturn(true);
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(3, "accept"));
        assertEquals("busy", lastResult());
        assertEquals(STEVE.toString(), answers.get(2).get("uuid"));
        assertEquals(3, answers.get(2).get("taskId"));

        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
    }

    @Test
    void handOff_ShowsAcceptDecline_AndAcceptClaims() {
        when(api.claim(eq(9), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.completedFuture(claimed(9)));

        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(9, "accept"));

        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
        verify(steve, atLeastOnce()).sendMessage(any(Component.class));
        assertEquals(List.of(Duration.ofSeconds(120)), delays);
        assertTrue(sync.openOffers().containsKey(9));

        sync.respond(steve, 9, true);

        verify(api).claim(eq(9), any(ClaimTaskDto.class));
        assertEquals(1, started.size());
        assertFalse(sync.openOffers().containsKey(9));
    }

    @Test
    void handOff_Decline_IsReported() {
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(10, "accept"));

        sync.respond(steve, 10, false);

        assertEquals("declined", lastResult());
        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
        assertTrue(sync.openOffers().isEmpty());
    }

    @Test
    void handOff_NoAnswer_TimesOut_AndALateClickFindsNothing() {
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(11, "accept"));

        now = now.plusSeconds(121);
        scheduled.forEach(Runnable::run);

        assertEquals("timed-out", lastResult());
        sync.respond(steve, 11, true);
        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
        verify(steve).sendMessage(org.mockito.ArgumentMatchers.contains("no open offer"));
    }

    @Test
    void someoneElseCannotAnswerAnOffer() {
        Player alex = mock(Player.class);
        when(alex.getUniqueId()).thenReturn(UUID.randomUUID());
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(12, "accept"));

        sync.respond(alex, 12, true);

        assertTrue(sync.openOffers().containsKey(12));
        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
    }

    @Test
    void cancelled_StopsTheHeldTask_AtOnce() {
        leases.hold(13, STEVE, "LocationSelection");
        when(registry.stopTask(steve, 13)).thenReturn(true);

        send(WorldTaskEvents.WORLD_TASK_CANCELLED, "{\"taskId\":13,\"status\":\"Cancelled\",\"uuid\":\"" + STEVE + "\"}");

        verify(registry).stopTask(steve, 13);
        assertFalse(leases.holds(13));
        verify(steve).sendMessage(org.mockito.ArgumentMatchers.contains("was cancelled in the web app"));
    }

    @Test
    void cancelled_ReleasedByReplace_SaysHandedBack() {
        leases.hold(14, STEVE, "LocationSelection");
        when(registry.stopTask(steve, 14)).thenReturn(true);

        send(WorldTaskEvents.WORLD_TASK_CANCELLED,
            "{\"taskId\":14,\"status\":\"Pending\",\"reason\":\"the web app sent you task #15 instead.\"}");

        verify(steve).sendMessage(org.mockito.ArgumentMatchers.contains("was handed back: the web app sent you task #15"));
    }

    @Test
    void cancelled_ATaskThisServerDoesntHold_ChangesNothing() {
        send(WorldTaskEvents.WORLD_TASK_CANCELLED, "{\"taskId\":99,\"status\":\"Cancelled\"}");

        verify(registry, never()).stopTask(any(), anyInt());
    }

    @Test
    void cancelledOrWithdrawn_DropsAnOpenOffer() {
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(16, "accept"));
        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(17, "accept"));
        // A second offer while one is open for the same player is answered busy.
        assertEquals(java.util.Set.of(16), sync.openOffers().keySet());
        assertEquals("busy", lastResult());

        send(WorldTaskEvents.WORLD_TASK_OFFER_WITHDRAWN, "{\"taskId\":16,\"uuid\":\"" + STEVE + "\",\"reason\":\"Claimed by Alex with the task code.\"}");

        assertTrue(sync.openOffers().isEmpty());
        verify(steve).sendMessage(org.mockito.ArgumentMatchers.contains("Claimed by Alex"));
    }

    @Test
    void created_RunsTheHeadlessPollNow() {
        send(WorldTaskEvents.WORLD_TASK_CREATED, "{\"taskId\":20,\"taskType\":\"GateBlockScan\"}");

        assertEquals(1, headlessPolls);
    }

    @Test
    void anAnswerThatCantBeSent_IsNotAnError() {
        hubUp = false;
        online.clear();

        send(WorldTaskEvents.WORLD_TASK_OFFERED, offer(21, "auto-claim"));

        assertEquals("offline", lastResult());
    }

    @Test
    void stopMessageAndDuration() {
        assertEquals("expired.", WorldTaskRealtimeSync.stopMessage("Expired", null));
        assertEquals("was handed back.", WorldTaskRealtimeSync.stopMessage("Pending", null));
        assertEquals("2 min", WorldTaskRealtimeSync.formatDuration(120));
        assertEquals("45 s", WorldTaskRealtimeSync.formatDuration(45));
    }
}
