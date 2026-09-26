package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;
import net.knightsandkings.knk.paper.user.MessagingService;
import net.knightsandkings.knk.paper.user.PrivateMessageLogger;
import net.knightsandkings.knk.paper.user.SpyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * KNG-18 Phase 1: /msg and /reply through MessagingService - vanish-safe lookup, console on both
 * ends, frozen players, rate limit, spy fan-out and the log (DESIGN.md §3.3). Phase 2: the ignore
 * gate's silent drop.
 */
class MessageCommandsTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Map<UUID, Set<String>> grants = new HashMap<>();
    private final AdminFreezeManager freeze = new AdminFreezeManager();
    private final SpyService spy = mock(SpyService.class);
    private final List<PrivateMessageLogger.Entry> logged = new ArrayList<>();
    private final Map<CommandSender, List<String>> inbox = new IdentityHashMap<>();
    /** (recipient, sender) pairs where the recipient ignores the sender. */
    private final Set<List<ParticipantId>> ignoring = new HashSet<>();

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player staff = player("Staff");
    private final ConsoleCommandSender console = console();
    private final Map<String, Player> byName = Map.of("alice", alice, "bob", bob, "staff", staff);
    private final Map<UUID, Player> byUuid = Map.of(
            alice.getUniqueId(), alice, bob.getUniqueId(), bob, staff.getUniqueId(), staff);

    private final VisiblePlayers visiblePlayers = new VisiblePlayers(
            name -> byName.get(name.toLowerCase()), byUuid::get, () -> List.of(alice, bob, staff));
    private final MessagingService service = new MessagingService(
            KnkConfig.PrivateMessagesConfig.defaults(), permissible, freeze, spy, logged::add,
            (recipient, sender) -> ignoring.contains(List.of(recipient, sender)), visiblePlayers,
            player -> null, () -> console, Runnable::run,
            Clock.fixed(Instant.parse("2026-09-26T19:04:00Z"), ZoneOffset.UTC));
    private final MessageCommand msg = new MessageCommand(service, visiblePlayers);
    private final ReplyCommand reply = new ReplyCommand(service);

    MessageCommandsTest() {
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv -> {
            OfflinePlayer who = inv.getArgument(0);
            String node = inv.getArgument(1);
            return CompletableFuture.completedFuture(grants.getOrDefault(who.getUniqueId(), Set.of()).contains(node));
        });
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.canSee(any(Player.class))).thenReturn(true);
        captureMessages(player);
        return player;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        when(sender.getName()).thenReturn("CONSOLE");
        captureMessages(sender);
        return sender;
    }

    private void captureMessages(CommandSender sender) {
        List<String> box = new ArrayList<>();
        inbox.put(sender, box);
        doAnswer(inv -> box.add(ChatColor.stripColor((String) inv.getArgument(0)))).when(sender).sendMessage(anyString());
        doAnswer(inv -> box.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument(0))))
                .when(sender).sendMessage(any(Component.class));
    }

    private List<String> inboxOf(CommandSender sender) {
        return inbox.get(sender);
    }

    private String lastOf(CommandSender sender) {
        List<String> box = inboxOf(sender);
        return box.isEmpty() ? null : box.get(box.size() - 1);
    }

    private void grant(Player player, String node) {
        grants.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add(node);
    }

    private static ParticipantId id(Player player) {
        return ParticipantId.player(player.getUniqueId());
    }

    private void msg(CommandSender sender, String... args) {
        msg.onCommand(sender, mock(Command.class), "msg", args);
    }

    private void reply(CommandSender sender, String... args) {
        reply.onCommand(sender, mock(Command.class), "r", args);
    }

    // ===== /msg =====

    @Test
    void delivers_echoes_spies_and_logs() {
        msg(alice, "Bob", "hi", "there");

        assertEquals(List.of("[me -> Bob] hi there"), inboxOf(alice));
        assertEquals(List.of("[Alice -> me] hi there"), inboxOf(bob));
        ParticipantId from = id(alice);
        ParticipantId to = id(bob);
        verify(spy).broadcast(eq(from), eq(to), any(Component.class));
        assertEquals(1, logged.size());
        assertEquals(PrivateMessageLogger.Outcome.DELIVERED, logged.get(0).outcome());
        assertEquals("hi there", logged.get(0).text());
        assertEquals(bob.getUniqueId(), logged.get(0).recipientUuid());
    }

    @Test
    void unknownOfflineAndVanishedTargets_getTheSameLine() {
        when(bob.isOnline()).thenReturn(false);
        when(alice.canSee(staff)).thenReturn(false);

        msg(alice, "Nobody", "hi");
        msg(alice, "Bob", "hi");
        msg(alice, "Staff", "hi");

        assertEquals(List.of(
                "No online player found named 'Nobody'.",
                "No online player found named 'Bob'.",
                "No online player found named 'Staff'."), inboxOf(alice));
        assertTrue(inboxOf(staff).isEmpty());
        verify(spy, never()).broadcast(any(), any(), any());
        assertTrue(logged.isEmpty());
    }

    @Test
    void tabCompletion_hidesVanishedPlayersAndSelf() {
        when(alice.canSee(staff)).thenReturn(false);

        assertEquals(List.of("Bob"), msg.onTabComplete(alice, mock(Command.class), "msg", new String[] {""}));
        assertEquals(List.of("Alice", "Bob", "Staff"), msg.onTabComplete(console, mock(Command.class), "msg", new String[] {""}));
        assertEquals(List.of("Staff"), msg.onTabComplete(bob, mock(Command.class), "msg", new String[] {"st"}));
        assertEquals(List.of(), msg.onTabComplete(alice, mock(Command.class), "msg", new String[] {"Bob", "h"}));
    }

    @Test
    void self_isRefused() {
        msg(alice, "alice", "hi");

        assertEquals(List.of("You can't message yourself."), inboxOf(alice));
    }

    @Test
    void usage_includesTheModerationNote() {
        msg(alice, "Bob");
        msg(alice, "Bob", "", "");

        String usage = "Usage: /msg <player> <message>\nPrivate messages may be read by moderators.";
        assertEquals(List.of(usage, usage), inboxOf(alice));
        assertTrue(inboxOf(bob).isEmpty());
    }

    // ===== /reply =====

    @Test
    void reply_goesToTheLastPartner() {
        msg(alice, "Bob", "hi");
        reply(bob, "yo");

        assertEquals("[Bob -> me] yo", lastOf(alice));
        assertTrue(logged.get(1).viaReply());
    }

    @Test
    void reply_withoutAPartner() {
        reply(alice, "hi");

        assertEquals(List.of("Nobody to reply to."), inboxOf(alice));
    }

    @Test
    void reply_toAPartnerWhoLeft() {
        msg(alice, "Bob", "hi");
        when(bob.isOnline()).thenReturn(false);

        reply(alice, "still there?");

        assertEquals("Bob is no longer online.", lastOf(alice));
    }

    @Test
    void reply_reachesAVanishedStaffMemberWhoMessagedYou() {
        when(alice.canSee(staff)).thenReturn(false);
        msg(staff, "Alice", "hello from staff");

        reply(alice, "ok");
        reply(alice, "and again");

        assertEquals("[Alice -> me] and again", lastOf(staff));
    }

    @Test
    void reply_doesNotRevealAStaffMemberWhoVanishedAfterBeingMessaged() {
        msg(alice, "Staff", "hi");
        when(alice.canSee(staff)).thenReturn(false);

        reply(alice, "hello?");

        assertEquals("No online player found named 'Staff'.", lastOf(alice));
    }

    @Test
    void usage_forReply() {
        reply(alice);

        assertEquals(List.of("Usage: /r <message>\nPrivate messages may be read by moderators."), inboxOf(alice));
    }

    // ===== console =====

    @Test
    void console_canMessage_beRepliedTo_andReply() {
        msg(console, "Alice", "hi");
        assertEquals("[CONSOLE -> me] hi", lastOf(alice));
        assertEquals("[me -> Alice] hi", lastOf(console));

        reply(alice, "ok");
        assertEquals("[Alice -> me] ok", lastOf(console));

        reply(console, "back");
        assertEquals("[CONSOLE -> me] back", lastOf(alice));
        assertEquals(null, logged.get(0).senderUuid());
    }

    // ===== frozen =====

    @Test
    void frozenPlayer_canMessageFreezeHolders_only() {
        freeze.freeze(alice.getUniqueId(), "test");
        grant(staff, PrivateMessageNodes.FREEZE);

        msg(alice, "Staff", "why?");
        msg(alice, "Bob", "help");

        assertEquals("[Alice -> me] why?", lastOf(staff));
        assertTrue(inboxOf(bob).isEmpty());
        assertEquals("You can only message staff while frozen.", lastOf(alice));
        assertEquals(PrivateMessageLogger.Outcome.BLOCKED_FROZEN, logged.get(1).outcome());
    }

    @Test
    void frozenPlayer_canReplyToStaff() {
        grant(staff, PrivateMessageNodes.FREEZE);
        freeze.freeze(alice.getUniqueId(), "test");
        msg(staff, "Alice", "you are frozen");

        reply(alice, "sorry");

        assertEquals("[Alice -> me] sorry", lastOf(staff));
    }

    // ===== rate limit =====

    @Test
    void sixthMessageInFiveSeconds_isRefused_andNotSpied() {
        for (int i = 1; i <= 6; i++) {
            msg(alice, "Bob", "message " + i);
        }

        assertEquals("Slow down — you can send another message in 5s.", lastOf(alice));
        assertEquals(5, inboxOf(bob).size());
        verify(spy, times(5)).broadcast(any(), any(), any());
        assertEquals(PrivateMessageLogger.Outcome.BLOCKED_RATE_LIMITED, logged.get(5).outcome());
    }

    @Test
    void rateLimitBypass() {
        grant(alice, PrivateMessageNodes.BYPASS_RATE_LIMIT);

        for (int i = 1; i <= 8; i++) {
            msg(alice, "Bob", "same");
        }

        assertEquals(8, inboxOf(bob).size());
    }

    // ===== ignore =====

    @Test
    void ignoredSender_seesTheNormalEcho_butNothingIsDelivered() {
        ignoring.add(List.of(id(bob), id(alice)));

        msg(alice, "Bob", "hello?");

        assertEquals(List.of("[me -> Bob] hello?"), inboxOf(alice));
        assertTrue(inboxOf(bob).isEmpty());
        verify(bob, never()).playSound(any(net.kyori.adventure.sound.Sound.class));
        org.mockito.ArgumentCaptor<Component> spyLine = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(spy).broadcast(eq(id(alice)), eq(id(bob)), spyLine.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(spyLine.getValue()).startsWith("[Spy][ignored] Alice -> Bob"));
        assertEquals(PrivateMessageLogger.Outcome.BLOCKED_IGNORED, logged.get(0).outcome());

        // No reply link either way.
        reply(bob, "hm");
        assertEquals("Nobody to reply to.", lastOf(bob));
        reply(alice, "again");
        assertEquals("Nobody to reply to.", lastOf(alice));
    }

    @Test
    void ignore_onlyAppliesOneWay() {
        ignoring.add(List.of(id(bob), id(alice)));

        msg(bob, "Alice", "you can't answer me");

        assertEquals("[Bob -> me] you can't answer me", lastOf(alice));
    }

    @Test
    void bypassIgnore_reachesThePlayerAnyway() {
        ignoring.add(List.of(id(bob), id(staff)));
        grant(staff, PrivateMessageNodes.BYPASS_IGNORE);

        msg(staff, "Bob", "staff here");

        assertEquals("[Staff -> me] staff here", lastOf(bob));
        assertEquals(PrivateMessageLogger.Outcome.DELIVERED, logged.get(0).outcome());
    }

    @Test
    void console_isNeverIgnored() {
        ignoring.add(List.of(id(bob), ParticipantId.CONSOLE));

        msg(console, "Bob", "server notice");

        assertEquals("[CONSOLE -> me] server notice", lastOf(bob));
    }

    @Test
    void quitting_dropsOwnReplyLink() {
        msg(alice, "Bob", "hi");

        service.forget(bob.getUniqueId());
        reply(bob, "hm");

        assertEquals("Nobody to reply to.", lastOf(bob));
    }
}
