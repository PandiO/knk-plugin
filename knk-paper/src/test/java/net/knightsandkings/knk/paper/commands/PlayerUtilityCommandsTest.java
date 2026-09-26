package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-9: /fly, /heal, /feed, /enderchest and /inventory - argument parsing, which KnkPermissible node
 * each form checks, the rank check on another player's storage, and the online-only behaviour.
 */
class PlayerUtilityCommandsTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final List<String> checkedNodes = new ArrayList<>();
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Map<String, Player> online = Map.of("alice", alice, "bob", bob);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
            name -> online.get(name.toLowerCase()), () -> List.of(alice, bob));

    private boolean rankAllows = true;
    private final List<Player> rankChecked = new ArrayList<>();
    private final TargetRankCheck rankCheck = (sender, target, onAllowed) -> {
        rankChecked.add(target);
        if (rankAllows) {
            onAllowed.run();
        }
    };

    PlayerUtilityCommandsTest() {
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv -> {
            String node = inv.getArgument(1);
            checkedNodes.add(node);
            return CompletableFuture.completedFuture(granted.contains(node));
        });
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void grant(String... nodes) {
        granted.addAll(List.of(nodes));
    }

    private static void run(org.bukkit.command.CommandExecutor executor, CommandSender sender, String... args) {
        executor.onCommand(sender, mock(Command.class), "cmd", args);
    }

    // ===== /fly =====

    @Test
    void flyWithoutArgumentsTogglesYourOwnFlightOnTheSelfNode() {
        grant(FlyCommand.NODE);
        when(alice.getAllowFlight()).thenReturn(false);

        run(new FlyCommand(support), alice);

        assertEquals(List.of("knk.fly"), checkedNodes);
        verify(alice).setAllowFlight(true);
    }

    @Test
    void flyOffForAnotherPlayerSetsItAndGroundsThem() {
        grant(FlyCommand.NODE_OTHERS);
        when(bob.getAllowFlight()).thenReturn(false); // "off" sets, it doesn't toggle

        run(new FlyCommand(support), alice, "off", "Bob");

        assertEquals(List.of("knk.fly.others"), checkedNodes);
        verify(bob).setAllowFlight(false);
        verify(bob).setFlying(false);
    }

    @Test
    void flyOnAnotherPlayerNeedsTheOthersNode() {
        grant(FlyCommand.NODE); // self only

        run(new FlyCommand(support), alice, "Bob");

        assertEquals(List.of("knk.fly.others"), checkedNodes);
        verify(bob, never()).setAllowFlight(anyBoolean());
        verify(alice).sendMessage(contains("don't have permission"));
    }

    @Test
    void flyNamingYourselfUsesTheSelfNode() {
        grant(FlyCommand.NODE);

        run(new FlyCommand(support), alice, "on", "alice");

        assertEquals(List.of("knk.fly"), checkedNodes);
        verify(alice).setAllowFlight(true);
    }

    @Test
    void flyOnAnOfflinePlayerChecksNothing() {
        run(new FlyCommand(support), alice, "Carol");

        assertTrue(checkedNodes.isEmpty());
        verify(alice).sendMessage(contains("No online player named 'Carol'"));
    }

    @Test
    void flyStateWords() {
        assertEquals(Boolean.TRUE, FlyCommand.parseState("ON"));
        assertEquals(Boolean.TRUE, FlyCommand.parseState("true"));
        assertEquals(Boolean.FALSE, FlyCommand.parseState("off"));
        assertEquals(Boolean.FALSE, FlyCommand.parseState("disable"));
        assertNull(FlyCommand.parseState("Bob"));
    }

    @Test
    void flyFromTheConsoleNeedsATarget() {
        CommandSender console = mock(CommandSender.class);

        run(new FlyCommand(support), console, "Bob");
        run(new FlyCommand(support), console);

        verify(bob).setAllowFlight(anyBoolean()); // console skips KnkPermissible
        verify(console).sendMessage(contains("Only players"));
    }

    // ===== /heal and /feed =====

    @Test
    void feedHasItsOwnNodesNotHeals() {
        grant("knk.heal", "knk.heal.others", "knk.heal.all"); // v2 bug: these used to allow /feed

        run(new RestoreCommand(support, RestoreCommand.Kind.FEED), alice);
        run(new RestoreCommand(support, RestoreCommand.Kind.FEED), alice, "Bob");
        run(new RestoreCommand(support, RestoreCommand.Kind.FEED), alice, "all");

        assertEquals(List.of("knk.feed", "knk.feed.others", "knk.feed.all"), checkedNodes);
        verify(alice, never()).setFoodLevel(anyInt());
        verify(bob, never()).setFoodLevel(anyInt());
    }

    @Test
    void feedAnotherPlayerFillsHungerAndSaturation() {
        grant("knk.feed.others");

        run(new RestoreCommand(support, RestoreCommand.Kind.FEED), alice, "Bob");

        verify(bob).setFoodLevel(20);
        verify(bob).setSaturation(20f);
        verify(bob).setExhaustion(0f);
        verify(bob).sendMessage(contains("fed by"));
    }

    @Test
    void feedAllSkipsDeadPlayersAndReportsTheCount() {
        grant("knk.feed.all");
        when(bob.isDead()).thenReturn(true);

        run(new RestoreCommand(support, RestoreCommand.Kind.FEED), alice, "all");

        verify(alice).setFoodLevel(20);
        verify(bob, never()).setFoodLevel(anyInt());
        verify(alice).sendMessage(contains("Fed 1 online player."));
    }

    @Test
    void healChecksTheHealNodes() {
        // Denied on purpose: Attribute.MAX_HEALTH needs a running server's registry, so the heal itself
        // can't run in a unit test - this covers the node routing only.
        run(new RestoreCommand(support, RestoreCommand.Kind.HEAL), alice);
        run(new RestoreCommand(support, RestoreCommand.Kind.HEAL), alice, "Bob");
        run(new RestoreCommand(support, RestoreCommand.Kind.HEAL), alice, "ALL");

        assertEquals(List.of("knk.heal", "knk.heal.others", "knk.heal.all"), checkedNodes);
        verify(bob, never()).setHealth(any(Double.class));
    }

    // ===== /enderchest =====

    @Test
    void enderchestWithoutArgumentsOpensYourOwn() {
        grant(EnderchestCommand.NODE);
        Inventory own = mock(Inventory.class);
        when(alice.getEnderChest()).thenReturn(own);

        run(new EnderchestCommand(support, rankCheck), alice);

        verify(alice).openInventory(own);
        assertTrue(rankChecked.isEmpty());
    }

    @Test
    void enderchestOfAnotherPlayerIsRankChecked() {
        grant(EnderchestCommand.NODE_OPEN);
        Inventory bobs = mock(Inventory.class);
        when(bob.getEnderChest()).thenReturn(bobs);

        run(new EnderchestCommand(support, rankCheck), alice, "open", "Bob");

        assertEquals(List.of("knk.enderchest.open"), checkedNodes);
        assertEquals(List.of(bob), rankChecked);
        verify(alice).openInventory(bobs);
    }

    @Test
    void enderchestOfAHigherRankedPlayerStaysClosed() {
        grant(EnderchestCommand.NODE_OPEN);
        rankAllows = false;

        run(new EnderchestCommand(support, rankCheck), alice, "Bob");

        verify(alice, never()).openInventory(any(Inventory.class));
    }

    @Test
    void enderchestCheckOnAnOfflinePlayerExplainsItIsNotSupported() {
        run(new EnderchestCommand(support, rankCheck), alice, "check", "Carol");

        verify(alice).sendMessage(contains("Offline ender chests can't be viewed yet"));
    }

    // ===== /inventory =====

    @Test
    void inventoryOpenShowsTheTargetsLiveInventory() {
        grant(InventoryCommand.NODE_OPEN);
        PlayerInventory bobs = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(bobs);

        run(new InventoryCommand(support, rankCheck), alice, "see", "Bob");

        assertEquals(List.of("knk.inventory.open"), checkedNodes);
        assertEquals(List.of(bob), rankChecked);
        verify(alice).openInventory(bobs);
    }

    @Test
    void inventoryClearWithoutConfirmOnlyWarns() {
        grant(InventoryCommand.NODE_CLEAR);
        PlayerInventory bobs = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(bobs);

        run(new InventoryCommand(support, rankCheck), alice, "clear", "Bob");

        verify(bobs, never()).clear();
        verify(alice).sendMessage(contains("can't be undone"));
    }

    @Test
    void inventoryClearWithConfirmClearsAfterTheRankCheck() {
        grant(InventoryCommand.NODE_CLEAR);
        PlayerInventory bobs = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(bobs);

        run(new InventoryCommand(support, rankCheck), alice, "clear", "Bob", "confirm");

        assertEquals(List.of(bob), rankChecked);
        verify(bobs).clear();
        verify(bob).sendMessage(contains("cleared by"));
    }

    @Test
    void inventoryClearIsDeniedWithoutTheNode() {
        PlayerInventory bobs = mock(PlayerInventory.class);
        when(bob.getInventory()).thenReturn(bobs);

        run(new InventoryCommand(support, rankCheck), alice, "clear", "Bob", "confirm");

        verify(bobs, never()).clear();
        assertTrue(rankChecked.isEmpty());
    }

    @Test
    void inventoryOnAnOfflinePlayerExplainsItIsNotSupported() {
        run(new InventoryCommand(support, rankCheck), alice, "open", "Carol");

        verify(alice).sendMessage(contains("Offline inventories can't be viewed or cleared yet"));
        assertTrue(checkedNodes.isEmpty());
    }

    // ===== tab completion =====

    @Test
    void tabCompletionOffersKeywordsAndOnlinePlayers() {
        assertEquals(List.of("Alice", "all"),
                new RestoreCommand(support, RestoreCommand.Kind.HEAL).onTabComplete(alice, mock(Command.class), "heal", new String[]{"a"}));
        assertEquals(List.of("Bob"),
                new FlyCommand(support).onTabComplete(alice, mock(Command.class), "fly", new String[]{"on", "b"}));
        assertEquals(List.of("confirm"),
                new InventoryCommand(support, rankCheck).onTabComplete(alice, mock(Command.class), "inventory", new String[]{"clear", "Bob", "c"}));
        assertFalse(new EnderchestCommand(support, rankCheck)
                .onTabComplete(alice, mock(Command.class), "ec", new String[]{""}).isEmpty());
    }
}
