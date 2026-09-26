package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.user.IgnoreService;

/** KNG-18 Phase 2: /ignore [player] and /unignore &lt;player&gt; (DESIGN.md §3.3.1/§3.3.5). */
class IgnoreCommandTest {

    private final UserIgnoresApi api = mock(UserIgnoresApi.class);
    private final Set<UUID> staffUuids = new HashSet<>();
    private final List<String> aliceInbox = new ArrayList<>();

    private final Player alice = player("Alice", aliceInbox);
    private final Player bob = player("Bob", new ArrayList<>());
    private final Player staff = player("Staff", new ArrayList<>());
    private final UserSummary aliceUser = new UserSummary(1, "Alice", alice.getUniqueId(), 0);
    private final UserSummary bobUser = new UserSummary(2, "Bob", bob.getUniqueId(), 0);
    private final UserSummary staffUser = new UserSummary(3, "Staff", staff.getUniqueId(), 0);
    private final UUID offlineUuid = UUID.nameUUIDFromBytes("Olaf".getBytes());
    private final UserSummary offlineUser = new UserSummary(4, "Olaf", offlineUuid, 0);
    private final Map<String, UserSummary> users = Map.of(
            "alice", aliceUser, "bob", bobUser, "staff", staffUser, "olaf", offlineUser);

    private final IgnoreService ignoreService = new IgnoreService(api,
            uuid -> uuid.equals(alice.getUniqueId()) ? 1 : null, uuid -> true,
            Clock.fixed(Instant.parse("2026-09-26T19:04:00Z"), ZoneOffset.UTC));
    private final IgnoreCommand.TargetResolver resolver = (sender, name, onFound) -> {
        UserSummary user = users.get(name.toLowerCase());
        if (user == null) {
            sender.sendMessage(ChatColor.RED + "No player found named '" + name + "'.");
            return;
        }
        onFound.accept(user);
    };
    private final VisiblePlayers visiblePlayers = new VisiblePlayers(
            name -> Map.of("alice", alice, "bob", bob, "staff", staff).get(name.toLowerCase()),
            uuid -> null, () -> List.of(alice, bob, staff));
    private final IgnoreCommand ignore = command(false);
    private final IgnoreCommand unignore = command(true);

    IgnoreCommandTest() {
        when(api.list(1)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(api.add(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(UserIgnoresApi.AddResult.IGNORED));
        when(api.remove(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private IgnoreCommand command(boolean removeOnly) {
        return new IgnoreCommand(ignoreService, resolver,
                uuid -> CompletableFuture.completedFuture(staffUuids.contains(uuid)),
                visiblePlayers, Runnable::run, Logger.getLogger("IgnoreCommandTest"), removeOnly);
    }

    private static Player player(String name, List<String> inbox) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.canSee(any(Player.class))).thenReturn(true);
        doAnswer(inv -> inbox.add(ChatColor.stripColor((String) inv.getArgument(0)))).when(player).sendMessage(anyString());
        return player;
    }

    private void run(IgnoreCommand command, CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), command == ignore ? "ignore" : "unignore", args);
    }

    private String last() {
        return aliceInbox.get(aliceInbox.size() - 1);
    }

    private void loaded() {
        assertTrue(ignoreService.load(alice.getUniqueId()).join());
    }

    @Test
    void ignore_thenUnignoreByToggle() {
        loaded();

        run(ignore, alice, "bob");
        assertEquals("You are now ignoring Bob. You won't see their messages or chat.", last());
        assertTrue(ignoreService.ignores(alice.getUniqueId(), bob.getUniqueId()));
        verify(api).add(1, 2);

        run(ignore, alice, "Bob");
        assertEquals("You are no longer ignoring Bob.", last());
        assertFalse(ignoreService.ignores(alice.getUniqueId(), bob.getUniqueId()));
        verify(api).remove(1, 2);
    }

    @Test
    void unignore_removes_andRefusesSomeoneNotListed() {
        loaded();
        run(ignore, alice, "Bob");

        run(unignore, alice, "Staff");
        assertEquals("You aren't ignoring Staff.", last());

        run(unignore, alice, "bob");
        assertEquals("You are no longer ignoring Bob.", last());
    }

    @Test
    void offlinePlayersCanBeIgnored() {
        loaded();

        run(ignore, alice, "Olaf");

        assertEquals("You are now ignoring Olaf. You won't see their messages or chat.", last());
        assertTrue(ignoreService.ignores(alice.getUniqueId(), offlineUuid));
    }

    @Test
    void staff_cannotBeIgnored_preCheck() {
        loaded();
        staffUuids.add(staff.getUniqueId());

        run(ignore, alice, "Staff");

        assertEquals("You can't ignore staff.", last());
        verify(api, never()).add(anyInt(), anyInt());
        assertFalse(ignoreService.ignores(alice.getUniqueId(), staff.getUniqueId()));
    }

    @Test
    void staff_cannotBeIgnored_apiRefusal() {
        loaded();
        when(api.add(1, 3)).thenReturn(CompletableFuture.completedFuture(UserIgnoresApi.AddResult.CANNOT_IGNORE_STAFF));

        run(ignore, alice, "Staff");

        assertEquals("You can't ignore staff.", last());
        assertFalse(ignoreService.ignores(alice.getUniqueId(), staff.getUniqueId()), "rolled back");
    }

    @Test
    void apiFailure_isReported_andRolledBack() {
        loaded();
        when(api.add(1, 2)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("API down")));

        run(ignore, alice, "Bob");

        assertEquals("Couldn't update your ignore list - try again later.", last());
        assertFalse(ignoreService.ignores(alice.getUniqueId(), bob.getUniqueId()));
    }

    @Test
    void self_unknown_andUsage() {
        loaded();

        run(ignore, alice, "alice");
        assertEquals("You can't ignore yourself.", last());
        run(ignore, alice, "Nobody");
        assertEquals("No player found named 'Nobody'.", last());
        run(ignore, alice, "a", "b");
        assertEquals("Usage: /ignore [player]", last());
        run(unignore, alice);
        assertEquals("Usage: /unignore <player>", last());
    }

    @Test
    void list_showsNamesAndDates() {
        UserIgnore entry = new UserIgnore(2, "Bob", bob.getUniqueId(), OffsetDateTime.parse("2026-09-01T10:00:00Z"));
        when(api.list(1)).thenReturn(CompletableFuture.completedFuture(List.of(entry)));
        loaded();

        run(ignore, alice);

        assertEquals(List.of("You are ignoring 1 player:", "- Bob (since 2026-09-01)"), aliceInbox);
    }

    @Test
    void list_whenEmpty() {
        loaded();

        run(ignore, alice);

        assertEquals("You aren't ignoring anyone. Use /ignore <player> to ignore someone.", last());
    }

    @Test
    void beforeTheListLoaded_asksToWait() {
        when(api.list(1)).thenReturn(new CompletableFuture<>());

        run(ignore, alice, "Bob");

        assertEquals("Your ignore list is still loading - try again in a moment.", last());
        verify(api, never()).add(anyInt(), anyInt());
    }

    @Test
    void console_isRefused() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        List<String> inbox = new ArrayList<>();
        doAnswer(inv -> inbox.add(ChatColor.stripColor((String) inv.getArgument(0)))).when(console).sendMessage(anyString());

        run(ignore, console, "Bob");

        assertEquals(List.of("Only players can use this command."), inbox);
    }

    @Test
    void tabCompletion() {
        loaded();
        run(ignore, alice, "Bob");

        assertEquals(List.of("Bob", "Staff"), ignore.onTabComplete(alice, mock(Command.class), "ignore", new String[] {""}));
        assertEquals(List.of("Bob"), unignore.onTabComplete(alice, mock(Command.class), "unignore", new String[] {"b"}));
        assertEquals(List.of(), unignore.onTabComplete(alice, mock(Command.class), "unignore", new String[] {"s"}));
    }
}
