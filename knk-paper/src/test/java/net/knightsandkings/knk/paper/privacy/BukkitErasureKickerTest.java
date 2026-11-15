package net.knightsandkings.knk.paper.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.privacy.AccountErasureService.Kick;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import net.knightsandkings.knk.core.privacy.LocalDataEraser;
import net.knightsandkings.knk.core.privacy.LocalDataEraser.Where;

/** PandiO/knk-workspace#184: an online player of an erased account is kicked with the neutral message. */
class BukkitErasureKickerTest {

    private static final UUID ONLINE = UUID.randomUUID();
    private static final UUID OFFLINE = UUID.randomUUID();

    @Test
    void kicksOnlyAnOnlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        Map<UUID, Player> online = Map.of(ONLINE, player);
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, online::get, "Your account data has been deleted.");

        assertTrue(kicker.kick(new ErasedAccount(1, 42, ONLINE)).join());
        verify(player).kick(Component.text("Your account data has been deleted."));

        assertFalse(kicker.kick(new ErasedAccount(2, 43, OFFLINE)).join());
        assertFalse(kicker.kick(new ErasedAccount(3, 44, null)).join());
    }

    @Test
    void aPlayerAlreadyLeaving_IsNotKicked() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(false);
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, uuid -> player, "x");

        assertFalse(kicker.kick(new ErasedAccount(1, 42, ONLINE)).join());
        verify(player, never()).kick(any(Component.class));
    }

    /** Decision 2026-11-06 on LR1-8: the BEFORE_KICK steps (the siege vault) run first, in the same main-thread task. */
    @Test
    void anOnlinePlayer_GetsTheVaultBackFirst_InTheSameMainThreadTask_ThenIsKicked() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        Runnable returned = mock(Runnable.class);
        List<String> ran = new ArrayList<>();
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("siege vault items", Where.BEFORE_KICK, a -> { returned.run(); ran.add("return"); return 12; });
        eraser.register("siege vault", Where.MAIN_THREAD, a -> { ran.add("discard"); return 1; });
        int[] tasks = {0};
        Executor main = command -> { tasks[0]++; command.run(); };
        BukkitErasureKicker kicker = new BukkitErasureKicker(main, uuid -> player, "gone", eraser);

        Kick kick = kicker.returnAndKick(new ErasedAccount(1, 42, ONLINE)).join();

        assertTrue(kick.kicked());
        assertEquals(Map.of("siege vault items", 12), kick.returned());
        assertTrue(kick.failed().isEmpty());
        assertEquals(List.of("return"), ran); // the deletion steps are the erasure service's, after the settle
        assertEquals(1, tasks[0]);
        InOrder order = Mockito.inOrder(returned, player);
        order.verify(returned).run();
        order.verify(player).kick(Component.text("gone"));
    }

    @Test
    void anOfflinePlayer_GetsNothingBack_TheVaultIsLeftToTheDiscardStep() {
        List<String> ran = new ArrayList<>();
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("siege vault items", Where.BEFORE_KICK, a -> { ran.add("return"); return 3; });
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, uuid -> null, "gone", eraser);

        Kick kick = kicker.returnAndKick(new ErasedAccount(2, 43, OFFLINE)).join();

        assertFalse(kick.kicked());
        assertTrue(kick.returned().isEmpty());
        assertTrue(ran.isEmpty());
        assertFalse(kicker.returnAndKick(new ErasedAccount(3, 44, null)).join().kicked());
    }

    @Test
    void aFailingReturn_IsReported_AndThePlayerIsStillKicked() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("siege vault items", Where.BEFORE_KICK, a -> { throw new IllegalStateException("world unloaded"); });
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, uuid -> player, "gone", eraser);

        Kick kick = kicker.returnAndKick(new ErasedAccount(4, 45, ONLINE)).join();

        assertTrue(kick.kicked());
        assertEquals(List.of("siege vault items"), kick.failed());
        verify(player).kick(Component.text("gone"));
    }
}
