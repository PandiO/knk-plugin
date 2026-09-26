package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.teleport.TeleportAudit;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staff teleport auditing (docs/specs/teleport/DESIGN.md §3.10, Phase 2): the right user ids and
 * target, the actor attribution, one retry, and never a failure that escapes.
 */
class TeleportAuditorTest {

    private final World world = mock(World.class);
    private final UsersCommandApi api = mock(UsersCommandApi.class);
    private final UsersCommandApi acting = mock(UsersCommandApi.class);
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player carol = player("Carol");
    private final Map<UUID, Integer> ids = Map.of(alice.getUniqueId(), 1, bob.getUniqueId(), 2, carol.getUniqueId(), 3);
    private final TeleportAuditor auditor = new TeleportAuditor(api,
        uuid -> CompletableFuture.completedFuture(ids.get(uuid)), Runnable::run);
    private final Location from = new Location(world, 0.5, 64, -3.5);
    private final Location to = new Location(world, 100.5, 70, 20.5);

    TeleportAuditorTest() {
        when(world.getName()).thenReturn("world");
        when(api.withActor(1)).thenReturn(acting);
        when(acting.recordTeleportAudit(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(api.recordTeleportAudit(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        return player;
    }

    private static void await(CompletableFuture<Void> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("audit future did not complete normally", ex);
        }
    }

    private TeleportAudit sentBy(UsersCommandApi sender) {
        ArgumentCaptor<TeleportAudit> captor = ArgumentCaptor.forClass(TeleportAudit.class);
        verify(sender).recordTeleportAudit(captor.capture());
        return captor.getValue();
    }

    @Test
    void staffGoingToAPlayerIsSentAsThatStaffMemberUnderTheVisitedPlayer() {
        await(auditor.record(TeleportPlan.staffToPlayer(alice, alice, bob, true), from, to));

        TeleportAudit audit = sentBy(acting);
        assertEquals(TeleportKind.STAFF, audit.kind());
        assertEquals(1, audit.actorUserId());
        assertEquals(1, audit.subjectUserId());
        assertEquals(2, audit.visitedUserId());
        assertEquals(2, audit.targetUserId());
        assertEquals(new TeleportAudit.Point("world", 0.5, 64, -3.5), audit.from());
        assertEquals(new TeleportAudit.Point("world", 100.5, 70, 20.5), audit.to());
        assertTrue(audit.silent());
        assertFalse(audit.console());
        verify(api, never()).recordTeleportAudit(any());
    }

    @Test
    void movingAnotherPlayerIsFiledUnderTheMovedPlayer() {
        await(auditor.record(TeleportPlan.staffToPlayer(alice, carol, bob, false), from, to));

        TeleportAudit audit = sentBy(acting);
        assertEquals(3, audit.subjectUserId());
        assertEquals(3, audit.targetUserId());
        assertFalse(audit.silent());
    }

    @Test
    void consoleTeleportIsSentWithoutAnActor() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        await(auditor.record(TeleportPlan.staffToPlayer(console, carol, bob, false), from, to));

        TeleportAudit audit = sentBy(api);
        assertNull(audit.actorUserId());
        assertTrue(audit.console());
        assertEquals(3, audit.targetUserId());
    }

    @Test
    void failedCallIsRetriedOnce() {
        when(acting.recordTeleportAudit(any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API down")))
            .thenReturn(CompletableFuture.completedFuture(null));

        await(auditor.record(TeleportPlan.staffToPlayer(alice, alice, bob, false), from, to));

        verify(acting, times(2)).recordTeleportAudit(any());
    }

    @Test
    void secondFailureIsOnlyLogged() {
        when(acting.recordTeleportAudit(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("API down")));

        await(auditor.record(TeleportPlan.staffToPlayer(alice, alice, bob, false), from, to));

        verify(acting, times(2)).recordTeleportAudit(any());
    }

    @Test
    void subjectWithoutAnAccountIsNotSent() {
        Player stranger = player("Stranger");

        await(auditor.record(TeleportPlan.staffToPlayer(alice, stranger, bob, false), from, to));

        verify(acting, never()).recordTeleportAudit(any());
        verify(api, never()).recordTeleportAudit(any());
    }

    @Test
    void failingIdLookupLeavesTheActorOutButStillAudits() {
        TeleportAuditor flaky = new TeleportAuditor(api, uuid -> uuid.equals(alice.getUniqueId())
            ? CompletableFuture.failedFuture(new RuntimeException("cache down"))
            : CompletableFuture.completedFuture(ids.get(uuid)), Runnable::run);

        await(flaky.record(TeleportPlan.staffToPlayer(alice, carol, bob, false), from, to));

        TeleportAudit audit = sentBy(api);
        assertNull(audit.actorUserId());
        assertEquals(3, audit.targetUserId());
    }
}
