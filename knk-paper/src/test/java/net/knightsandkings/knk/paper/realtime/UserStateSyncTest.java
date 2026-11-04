package net.knightsandkings.knk.paper.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.UserStateEvents;
import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#179 (S2, DESIGN §6.1): what each user-state event does on the game server. Runs every
 * "main thread" hop inline; players are Mockito mocks (no Location involved).
 */
class UserStateSyncTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Player> online = new HashMap<>();
    private final UserCache userCache = new UserCache(Duration.ofMinutes(1));
    private final UsersDataAccess users = mock(UsersDataAccess.class);
    private final PermissionsDataAccess permissions = mock(PermissionsDataAccess.class);
    private final PermissionGroupsDataAccess groups = mock(PermissionGroupsDataAccess.class);
    private final OfflineSecurityStore offline = new OfflineSecurityStore(null, OfflineSecurityStore.Settings.defaults(), Clock.systemUTC());
    private final AdminFreezeManager freeze = new AdminFreezeManager();
    private final List<String> display = new ArrayList<>();
    private final List<PlayerNotification> notifications = new ArrayList<>();
    private UserStateSync sync;
    private RealtimeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        UserStateSync.Display recording = new UserStateSync.Display() {
            @Override
            public void redraw(Player player, UserSummary fresh) {
                display.add("redraw " + player.getName() + " xp=" + fresh.experiencePoints());
            }

            @Override
            public void permissionsApplied(Player player) {
                display.add("permissions " + player.getName());
            }

            @Override
            public void applyMode(Player player, ActiveMode mode) {
                display.add("mode " + player.getName() + " " + mode);
            }
        };
        freeze.setOfflineStore(offline);
        sync = new UserStateSync(Runnable::run, online::get, () -> (Collection<? extends Player>) online.values(), userCache,
            users, permissions, groups, offline, freeze, recording, notifications::add, Logger.getAnonymousLogger());
        dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        sync.register(dispatcher);
        when(permissions.preload(anyInt())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private Player online(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        online.put(uuid, player);
        return player;
    }

    private static UserSummary user(int id, UUID uuid, int xp, List<PermissionGroupRef> groups) {
        return new UserSummary(id, "u" + id, uuid, null, 100, 5, xp, true, false, GatePassThroughMethod.DEFAULT,
            ActiveMode.NONE, null, null, 0, null, null, null, false, null, null, null, null, null, groups);
    }

    private void fresh(UUID uuid, UserSummary summary) {
        when(users.refreshAsync(uuid)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(summary)));
    }

    private void send(String type, String payload) {
        assertTrue(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":2,\"type\":\"" + type + "\",\"payload\":"
            + payload + "}", mapper)));
    }

    @Test
    void registersEveryS2Type() {
        assertTrue(dispatcher.types().containsAll(UserStateEvents.TYPES));
    }

    @Test
    void permissionsChanged_OnlinePlayer_DropsSetAndOfflineAnswers_ThenReloadsRereadsAndRedraws() {
        online(ALICE, "Alice");
        offline.recordPermission(42, "knk.mode.staff", true);
        fresh(ALICE, user(42, ALICE, 10, List.of()));

        send(UserStateEvents.USER_PERMISSIONS_CHANGED, "{\"userId\":42,\"uuid\":\"" + ALICE + "\",\"reason\":\"GroupAssigned\"}");

        assertEquals(Optional.empty(), offline.permission(42, "knk.mode.staff"));
        verify(permissions).invalidateEffective(42);
        verify(permissions).preload(42);
        assertEquals(List.of("redraw Alice xp=10", "permissions Alice"), display);
    }

    @Test
    void permissionsChanged_OfflinePlayer_OnlyDropsTheCaches() {
        offline.recordPermission(42, "knk.mode.staff", true);

        send(UserStateEvents.USER_PERMISSIONS_CHANGED, "{\"userId\":42,\"uuid\":\"" + ALICE + "\",\"reason\":\"RankExpired\"}");

        verify(permissions).invalidateEffective(42);
        verify(permissions, never()).preload(anyInt());
        verify(users, never()).refreshAsync(any());
        assertEquals(Optional.empty(), offline.permission(42, "knk.mode.staff"));
        assertTrue(display.isEmpty());
    }

    @Test
    void groupChanged_FansOutToOnlineMembersOnly() {
        online(ALICE, "Alice");
        online(BOB, "Bob");
        userCache.put(user(1, ALICE, 0, List.of(new PermissionGroupRef(40, "Builders"), new PermissionGroupRef(2, "Default"))));
        userCache.put(user(2, BOB, 0, List.of(new PermissionGroupRef(2, "Default"))));
        fresh(ALICE, user(1, ALICE, 0, List.of()));

        send(UserStateEvents.PERMISSION_GROUP_CHANGED, "{\"groupId\":40,\"op\":\"GrantsChanged\"}");

        verify(groups).invalidate();
        verify(permissions).invalidateEffective(1);
        verify(permissions, never()).invalidateEffective(2);
        assertEquals(List.of("redraw Alice xp=0", "permissions Alice"), display);
    }

    @Test
    void groupCreated_OnlyDropsTheGroupList() {
        online(ALICE, "Alice");
        userCache.put(user(1, ALICE, 0, List.of()));

        send(UserStateEvents.PERMISSION_GROUP_CHANGED, "{\"groupId\":41,\"op\":\"Created\"}");

        verify(groups).invalidate();
        verify(permissions, never()).invalidateEffective(anyInt());
    }

    @Test
    void balancesChanged_UpdatesTheCache_AndRereadsOnlyWhenXpChanged() {
        online(ALICE, "Alice");
        userCache.put(user(1, ALICE, 40, List.of()));
        fresh(ALICE, user(1, ALICE, 90, List.of()));

        send(UserStateEvents.USER_BALANCES_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"coins\":150,\"gems\":6,\"xp\":40}");
        UserSummary cached = userCache.getStale(ALICE).orElseThrow();
        assertEquals(150, cached.coins());
        assertEquals(6, cached.gems());
        verify(users, never()).refreshAsync(any());

        send(UserStateEvents.USER_BALANCES_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"coins\":150,\"gems\":6,\"xp\":90}");
        verify(users).refreshAsync(ALICE);
        assertEquals(List.of("redraw Alice xp=90"), display);
    }

    @Test
    void frozenChanged_FreezesOnceAndTellsThePlayerOnce_ThenUnfreezes() {
        Player alice = online(ALICE, "Alice");
        String frozen = "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"frozen\":true,\"reason\":\"griefing\",\"by\":9}";

        send(UserStateEvents.USER_FROZEN_CHANGED, frozen);
        send(UserStateEvents.USER_FROZEN_CHANGED, frozen); // echo / duplicate

        assertTrue(freeze.isFrozen(ALICE));
        assertEquals("griefing", freeze.reasonFor(ALICE));
        verify(alice, times(1)).sendMessage("§cYou have been frozen: griefing");

        send(UserStateEvents.USER_FROZEN_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"frozen\":false}");
        assertFalse(freeze.isFrozen(ALICE));
        verify(alice).sendMessage("§aYou have been unfrozen.");
    }

    @Test
    void frozenChanged_OfflinePlayer_IsKeptForTheirNextJoinOnly() {
        userCache.put(user(1, ALICE, 0, List.of()));

        send(UserStateEvents.USER_FROZEN_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"frozen\":true,\"reason\":\"afk\"}");

        assertFalse(freeze.isFrozen(ALICE));
        assertTrue(userCache.getStale(ALICE).orElseThrow().isFrozen());
    }

    @Test
    void modeChanged_UpdatesTheStoredMode_AndAppliesItToAnOnlinePlayer() {
        online(ALICE, "Alice");
        userCache.put(user(1, ALICE, 0, List.of()));

        send(UserStateEvents.USER_MODE_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"mode\":\"Staff\"}");

        assertEquals(ActiveMode.STAFF, userCache.getStale(ALICE).orElseThrow().activeMode());
        assertEquals(List.of("mode Alice STAFF"), display);
    }

    @Test
    void userChanged_RereadsAnOnlinePlayer_AndDropsAnOfflineOne() {
        online(ALICE, "Alice");
        fresh(ALICE, user(1, ALICE, 5, List.of()));

        send(UserStateEvents.USER_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"fields\":[\"multipliers\"]}");
        send(UserStateEvents.USER_CHANGED, "{\"userId\":2,\"uuid\":\"" + BOB + "\",\"fields\":[\"link\"]}");

        assertEquals(List.of("redraw Alice xp=5"), display);
        verify(users).invalidate(BOB);
    }

    @Test
    void playerNotification_GoesToTheDelivery() {
        send(UserStateEvents.PLAYER_NOTIFICATION, "{\"id\":17,\"userId\":1,\"uuid\":\"" + ALICE
            + "\",\"username\":\"alice\",\"type\":\"RankChanged\"}");

        assertEquals(17L, notifications.get(0).id());
    }

    @Test
    void unusablePayloadIsIgnored() {
        send(UserStateEvents.USER_FROZEN_CHANGED, "{\"userId\":1}");
        send(UserStateEvents.USER_MODE_CHANGED, "{\"userId\":1,\"uuid\":\"" + ALICE + "\",\"mode\":\"Invisible\"}");

        assertTrue(display.isEmpty());
        assertFalse(freeze.isFrozen(ALICE));
    }

    @Test
    void resync_RereadsEveryOnlinePlayer_AndAppliesFreezeAndMode() {
        Player alice = online(ALICE, "Alice");
        userCache.put(user(1, ALICE, 0, List.of()));
        offline.recordPermission(1, "knk.socialspy", true);
        UserSummary frozenStaff = new UserSummary(1, "u1", ALICE, null, 100, 5, 0, true, false, GatePassThroughMethod.DEFAULT,
            ActiveMode.STAFF, null, null, 0, null, null, null, true, "web freeze", null, null, null, null, List.of());
        fresh(ALICE, frozenStaff);

        sync.resyncOnlinePlayers();

        assertEquals(Optional.empty(), offline.permission(1, "knk.socialspy"));
        assertTrue(freeze.isFrozen(ALICE));
        verify(alice).sendMessage(anyString());
        assertEquals(List.of("redraw Alice xp=0", "mode Alice STAFF", "permissions Alice"), display);
    }
}
