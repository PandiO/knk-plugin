package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/** KNG-58: a frozen player stays frozen, and a vanished staff member hidden, across a restart while the API is down. */
class OfflineFreezeAndModeTest {

    private final UUID uuid = UUID.randomUUID();
    private final OfflineSecurityStore store = new OfflineSecurityStore(null, OfflineSecurityStore.Settings.defaults(), Clock.systemUTC());
    private final Plugin plugin = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Griefer");
        when(player.isOnline()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private static UserSummary user(UUID uuid, ActiveMode mode, boolean frozen) {
        return new UserSummary(7, "Griefer", uuid, null, 0, 0, 0, true, false, GatePassThroughMethod.DEFAULT, mode,
            null, null, 0, null, null, null, frozen, frozen ? "griefing" : null);
    }

    private UsersDataAccess apiDown() {
        UsersDataAccess users = mock(UsersDataAccess.class);
        when(users.getByUsernameAsync(anyString()))
            .thenReturn(CompletableFuture.completedFuture(FetchResult.error(new RuntimeException("Connection refused"))));
        return users;
    }

    @Test
    void aFrozenPlayerWhoRejoinsDuringAnOutageIsStillFrozen() {
        store.recordUser(user(uuid, ActiveMode.NONE, true));
        AdminFreezeManager freezes = new AdminFreezeManager();
        freezes.setOfflineStore(store);

        freezes.restoreOnJoin(plugin, player, apiDown());

        assertTrue(freezes.isFrozen(uuid));
        assertEquals("griefing", freezes.reasonFor(uuid));
    }

    @Test
    void aFreezeMadeOnThisServerIsKeptForTheNextJoin() {
        store.recordUser(user(uuid, ActiveMode.NONE, false));
        AdminFreezeManager before = new AdminFreezeManager();
        before.setOfflineStore(store);
        before.freeze(uuid, "spam");

        AdminFreezeManager afterRestart = new AdminFreezeManager();
        afterRestart.setOfflineStore(store);
        afterRestart.restoreOnJoin(plugin, player, apiDown());

        assertTrue(afterRestart.isFrozen(uuid));
    }

    @Test
    void anUnfrozenPlayerStaysFreeDuringAnOutage() {
        store.recordUser(user(uuid, ActiveMode.NONE, false));
        AdminFreezeManager freezes = new AdminFreezeManager();
        freezes.setOfflineStore(store);

        freezes.restoreOnJoin(plugin, player, apiDown());

        assertFalse(freezes.isFrozen(uuid));
    }

    @Test
    void aVanishedStaffMemberKeepsTheirModeAfterARestart() {
        store.recordUser(user(uuid, ActiveMode.STAFF, false));
        KnkPermissible permissible = new KnkPermissible(new UserCache(Duration.ofMinutes(1)),
            new PermissionsDataAccess(Duration.ofSeconds(30), mock(PermissionsApi.class)), store);
        ModeService modes = new ModeService(plugin, permissible, new UserCache(Duration.ofMinutes(1)), null);
        modes.setOfflineStore(store);

        assertEquals(ActiveMode.STAFF, modes.getPersistedMode(player));
    }

    @Test
    void aModeChangeDuringAnOutageIsKept() {
        store.recordUser(user(uuid, ActiveMode.NONE, false));
        KnkPermissible permissible = new KnkPermissible(new UserCache(Duration.ofMinutes(1)),
            new PermissionsDataAccess(Duration.ofSeconds(30), mock(PermissionsApi.class)), store);
        ModeService modes = new ModeService(plugin, permissible, new UserCache(Duration.ofMinutes(1)), null);
        modes.setOfflineStore(store);

        modes.persist(player, ActiveMode.OWNER).join();

        assertEquals(ActiveMode.OWNER, store.identity(uuid).orElseThrow().activeMode());
    }
}
