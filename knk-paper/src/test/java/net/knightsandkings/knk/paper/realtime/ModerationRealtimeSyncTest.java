package net.knightsandkings.knk.paper.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.ModerationEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.realtime.RecentActionIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#574 (DESIGN §6.10): what {@code PlayerKick} / {@code PlayerBan} do on the game server. The "main
 * thread" runs inline and counts its tasks; players are Mockito mocks; the ban list is an in-memory fake.
 */
class ModerationRealtimeSyncTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Player> online = new HashMap<>();
    private final FakeBans bans = new FakeBans();
    private final List<LogRecord> logs = new ArrayList<>();
    private int mainThreadTasks;
    private RealtimeDispatcher dispatcher;

    /** Paper's profile ban list, as far as the handler uses it. */
    static final class FakeBans implements ModerationRealtimeSync.Bans {
        final Set<UUID> banned = new HashSet<>();
        final List<String> added = new ArrayList<>();
        RuntimeException failure;

        @Override
        public boolean isBanned(UUID uuid, String username) {
            return banned.contains(uuid);
        }

        @Override
        public void ban(UUID uuid, String username, String reason, String source) {
            if (failure != null) {
                throw failure;
            }
            banned.add(uuid);
            added.add(uuid + "|" + username + "|" + reason + "|" + source);
        }
    }

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
        ModerationRealtimeSync sync = new ModerationRealtimeSync(runnable -> {
            mainThreadTasks++;
            runnable.run();
        }, online::get, bans, new ModerationSettings("&cKicked by staff, {player}.", "Banned: {reason} (by {staff})", null),
            new RecentActionIds(), logger);
        dispatcher = new RealtimeDispatcher(logger);
        sync.register(dispatcher);
    }

    private Player online(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        online.put(uuid, player);
        return player;
    }

    private void send(String type, String payload) {
        assertTrue(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"" + type + "\","
            + "\"actor\":{\"userId\":7,\"source\":\"web\"},\"payload\":" + payload + "}", mapper)));
    }

    private static String payload(String actionId, String reason) {
        return "{\"actionId\":\"" + actionId + "\",\"userId\":42,\"uuid\":\"" + STEVE + "\",\"username\":\"Steve\","
            + "\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
            + ",\"staffUserId\":7,\"staffUsername\":\"Mod\",\"at\":\"2026-11-15T12:00:00Z\"}";
    }

    private static String kickText(Player player, PlayerKickEvent.Cause cause) {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player).kick(message.capture(), eq(cause));
        return PlainTextComponentSerializer.plainText().serialize(message.getValue());
    }

    @Test
    void registersBothTypesVersion1() {
        assertTrue(dispatcher.types().containsAll(List.of(ModerationEvents.PLAYER_KICK, ModerationEvents.PLAYER_BAN)));
    }

    @Test
    void kick_OnlinePlayer_WithReason() {
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_KICK, payload("k1", "Spamming chat"));
        assertEquals("Spamming chat", kickText(steve, PlayerKickEvent.Cause.KICK_COMMAND));
        assertEquals(1, mainThreadTasks, "the kick runs on the main thread");
        assertTrue(bans.added.isEmpty(), "a kick never bans");
    }

    @Test
    void kick_WithoutReason_DefaultMessage() {
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_KICK, payload("k2", null));
        assertEquals("Kicked by staff, Steve.", kickText(steve, PlayerKickEvent.Cause.KICK_COMMAND));
    }

    @Test
    void kick_NotOnlineHere_Nothing() {
        send(ModerationEvents.PLAYER_KICK, payload("k3", "x"));
        assertTrue(bans.added.isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("not online on this server")));
    }

    @Test
    void ban_OnlinePlayer_BanListEntryAndKick() {
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_BAN, payload("b1", "X-ray"));
        assertEquals(List.of(STEVE + "|Steve|X-ray|Mod"), bans.added, "permanent, reason, staff as source");
        assertEquals("Banned: X-ray (by Mod)", kickText(steve, PlayerKickEvent.Cause.BANNED));
    }

    @Test
    void ban_OfflinePlayer_BanListEntryOnly() {
        send(ModerationEvents.PLAYER_BAN, payload("b2", "Griefing"));
        assertEquals(List.of(STEVE + "|Steve|Griefing|Mod"), bans.added);
    }

    @Test
    void ban_AlreadyBanned_EntryKept_StillKickedIfOnline() {
        bans.banned.add(STEVE);
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_BAN, payload("b3", "Again"));
        assertTrue(bans.added.isEmpty(), "the existing entry is not replaced");
        verify(steve).kick(any(Component.class), eq(PlayerKickEvent.Cause.BANNED));
    }

    @Test
    void ban_WithoutStaffName_DefaultSource() {
        send(ModerationEvents.PLAYER_BAN, "{\"actionId\":\"b4\",\"userId\":42,\"uuid\":\"" + STEVE + "\",\"reason\":\"x\"}");
        assertEquals(List.of(STEVE + "|null|x|" + ModerationSettings.DEFAULT_BAN_SOURCE), bans.added);
    }

    @Test
    void ban_BanListFails_StillKickedAndLogged() {
        bans.failure = new IllegalStateException("disk full");
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_BAN, payload("b5", "X-ray"));
        verify(steve).kick(any(Component.class), eq(PlayerKickEvent.Cause.BANNED));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE));
    }

    @Test
    void repeatedActionId_ActsOnce() {
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_KICK, payload("same", "x"));
        send(ModerationEvents.PLAYER_KICK, payload("same", "x"));
        verify(steve, times(1)).kick(any(Component.class), any(PlayerKickEvent.Cause.class));
        assertEquals(1, mainThreadTasks);

        bans.banned.clear();
        send(ModerationEvents.PLAYER_BAN, payload("ban-once", "x"));
        bans.banned.clear(); // even if the entry were gone (a /pardon), the same action is not carried out again
        send(ModerationEvents.PLAYER_BAN, payload("ban-once", "x"));
        assertEquals(1, bans.added.size());
    }

    @Test
    void malformedPayload_LoggedIgnoredNoCrash() {
        Player steve = online(STEVE, "Steve");
        send(ModerationEvents.PLAYER_BAN, "{\"userId\":42,\"uuid\":\"" + STEVE + "\",\"reason\":\"x\"}");
        send(ModerationEvents.PLAYER_KICK, "{\"actionId\":\"m1\",\"userId\":42,\"uuid\":\"nope\"}");
        send(ModerationEvents.PLAYER_KICK, "\"just text\"");
        verify(steve, never()).kick(any(Component.class), any(PlayerKickEvent.Cause.class));
        assertTrue(bans.added.isEmpty());
        assertEquals(0, mainThreadTasks);
        assertEquals(3, logs.stream().filter(r -> r.getLevel() == Level.WARNING).count());
    }

    @Test
    void newerVersion_IgnoredByTheDispatcher() {
        Player steve = online(STEVE, "Steve");
        assertFalse(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":2,\"seq\":3,\"type\":\"PlayerKick\",\"payload\":"
            + payload("v2", "x") + "}", mapper)));
        verify(steve, never()).kick(any(Component.class), any(PlayerKickEvent.Cause.class));
    }
}
