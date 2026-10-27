package net.knightsandkings.knk.paper.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/** PandiO/knk-workspace#171: who another player's teleport may move, and the {@code all} selection. */
class TeleportExemptionsTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    /** player name → nodes they hold. */
    private final Map<String, Set<String>> grants = new HashMap<>();
    private final Player staff = player("Staff");
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player carol = player("Carol");
    private final Player dave = player("Dave");
    private final List<Player> online = List.of(staff, alice, bob, carol, dave);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        name -> null, () -> online);
    private final Set<Player> inMode = new HashSet<>();
    private final Set<Player> higherRanked = new HashSet<>();
    private boolean overrides;
    private final TeleportExemptions exemptions = new TeleportExemptions(support, inMode::contains,
        (sender, target) -> CompletableFuture.completedFuture(!higherRanked.contains(target)),
        sender -> CompletableFuture.completedFuture(overrides));

    TeleportExemptionsTest() {
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv -> {
            Player player = inv.getArgument(0);
            boolean held = grants.getOrDefault(player.getName(), Set.of()).contains(inv.<String>getArgument(1));
            return CompletableFuture.completedFuture(PermissionDecision.of(held));
        });
        when(permissible.hasPermission(any(Player.class), anyString())).thenAnswer(inv ->
            grants.getOrDefault(inv.<Player>getArgument(0).getName(), Set.of()).contains(inv.<String>getArgument(1)));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void grant(Player player, String node) {
        grants.computeIfAbsent(player.getName(), k -> new HashSet<>()).add(node);
    }

    private List<Player> all(CommandSender sender, TeleportExemptions.Type type, Set<UUID> excluded) {
        List<Player> result = new ArrayList<>();
        exemptions.forAll(sender, type, excluded, result::addAll);
        return result;
    }

    @Test
    void nodesPerType() {
        assertEquals("knk.teleport.exempt.hub", TeleportExemptions.Type.HUB.exemptNode());
        assertEquals("knk.teleport.exempt.tphere", TeleportExemptions.Type.TPHERE.exemptNode());
        assertEquals("knk.teleport.all.spawn", TeleportExemptions.Type.SPAWN.allNode());
        assertEquals(List.of("knk.teleport.all.hub", "knk.teleport.all.spawn", "knk.teleport.all.tp", "knk.teleport.all.tphere"),
            TeleportExemptions.allNodes());
    }

    @Test
    void allTakesSameRankOrLowerNotExemptNotInAModeAndNeverTheSender() {
        grant(bob, TeleportExemptions.Type.HUB.exemptNode());
        inMode.add(carol);
        higherRanked.add(dave);

        assertEquals(List.of(alice), all(staff, TeleportExemptions.Type.HUB, Set.of()));
    }

    @Test
    void anExemptionOfAnotherTypeDoesNotCount() {
        grant(bob, TeleportExemptions.Type.SPAWN.exemptNode());

        assertTrue(all(staff, TeleportExemptions.Type.HUB, Set.of()).contains(bob));
    }

    @Test
    void excludedPlayersAreLeftOut() {
        assertEquals(List.of(bob, carol, dave), all(staff, TeleportExemptions.Type.TP, Set.of(alice.getUniqueId())));
    }

    @Test
    void theConsoleMovesEveryoneButStillNotExemptPlayers() {
        CommandSender console = mock(CommandSender.class);
        overrides = true;
        grant(bob, TeleportExemptions.Type.SPAWN.exemptNode());
        inMode.add(carol);

        assertEquals(List.of(staff, alice, dave), all(console, TeleportExemptions.Type.SPAWN, Set.of()));
    }

    @Test
    void singleTeleportRefusesAnExemptPlayer() {
        grant(bob, TeleportExemptions.Type.TPHERE.exemptNode());
        List<String> ran = new ArrayList<>();

        exemptions.whenNotExempt(staff, bob, TeleportExemptions.Type.TPHERE, () -> ran.add("bob"));
        exemptions.whenNotExempt(staff, alice, TeleportExemptions.Type.TPHERE, () -> ran.add("alice"));

        assertEquals(List.of("alice"), ran);
        verify(staff).sendMessage(contains("Bob is exempt from being teleported by others"));
    }

    @Test
    void singleTeleportRefusesStaffOrOwnerMode() {
        inMode.add(alice);
        List<String> ran = new ArrayList<>();

        exemptions.whenNotExempt(staff, alice, TeleportExemptions.Type.HUB, () -> ran.add("alice"));

        assertTrue(ran.isEmpty());
        verify(staff).sendMessage(contains("Alice is in staff or owner mode"));
    }

    @Test
    void overrideMovesAnExemptPlayerOnASingleTeleport() {
        inMode.add(alice);
        grant(bob, TeleportExemptions.Type.TP.exemptNode());
        overrides = true;
        List<String> ran = new ArrayList<>();

        exemptions.whenNotExempt(staff, alice, TeleportExemptions.Type.TP, () -> ran.add("alice"));
        exemptions.whenNotExempt(staff, bob, TeleportExemptions.Type.TP, () -> ran.add("bob"));

        assertEquals(List.of("alice", "bob"), ran);
    }

    @Test
    void runAllReportsHowManyWereMoved() {
        CommandSender sender = mock(CommandSender.class);

        TeleportExemptions.runAll(sender, List.of(alice, bob), "to the hub", player -> CompletableFuture.completedFuture(
            player == alice ? TeleportOutcome.teleported() : TeleportOutcome.failed("frozen")));

        verify(sender).sendMessage(contains("Teleported 1 of 2 players to the hub."));
        verify(sender).sendMessage(contains("Not moved: Bob"));
    }

    @Test
    void runAllWithNobodySaysSo() {
        CommandSender sender = mock(CommandSender.class);

        TeleportExemptions.runAll(sender, List.of(), "to you", player -> { throw new AssertionError(); });

        verify(sender).sendMessage(contains("Nobody to teleport"));
    }

    @Test
    void offersAllOnlyToHoldersOfItsNode() {
        assertFalse(exemptions.offersAll(staff, TeleportExemptions.Type.HUB, "a"));
        grant(staff, TeleportExemptions.Type.HUB.allNode());
        assertTrue(exemptions.offersAll(staff, TeleportExemptions.Type.HUB, "a"));
        assertFalse(exemptions.offersAll(staff, TeleportExemptions.Type.HUB, "b"));
    }
}
