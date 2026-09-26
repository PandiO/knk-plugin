package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP2: {@code /kit get|purchase} delegate to the same {@link KitGrantFlow} the menu uses. */
class KitCommandTest {

    private final KitsDataAccess kits = mock(KitsDataAccess.class);
    private final KitGrantFlow flow = mock(KitGrantFlow.class);
    private final Plugin plugin = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private final KitCommand command = new KitCommand(plugin, kits, flow);

    private void run(String... args) {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            command.onCommand(player, mock(Command.class), "kit", args);
        }
    }

    private void kitNamed(String name, int id) {
        KnkKit kit = new KnkKit(id, name, null, null, null, null, null, null, null, List.of(), null, null, null,
                false, 0, null, null, false, null);
        when(kits.searchAsync(any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(kit), 1, 1, 50)));
    }

    @Test
    void getClaimsThroughTheSharedFlow() {
        when(flow.requirePermission(player, "knk.kit.get")).thenReturn(true);
        when(flow.requireUserId(player)).thenReturn(42);
        kitNamed("Starter", 7);

        run("get", "starter");

        verify(flow).claim(player, 42, 7, "starter");
    }

    @Test
    void purchaseBuysThroughTheSharedFlow() {
        when(flow.requirePermission(player, "knk.kit.purchase")).thenReturn(true);
        when(flow.requireUserId(player)).thenReturn(42);
        kitNamed("Royal", 9);

        run("purchase", "Royal");

        verify(flow).purchase(player, 42, 9, "Royal");
    }

    @Test
    void unknownKitNeverReachesTheFlow() {
        when(flow.requirePermission(player, "knk.kit.get")).thenReturn(true);
        when(flow.requireUserId(player)).thenReturn(42);
        kitNamed("Starter", 7);

        run("get", "Nope");

        verify(flow, never()).claim(any(), anyInt(), anyInt(), anyString());
        verify(player).sendMessage("§cNo kit named \"Nope\" found.");
    }

    @Test
    void missingNodeStopsBeforeAnyLookup() {
        when(flow.requirePermission(player, "knk.kit.get")).thenReturn(false);

        run("get", "Starter");

        verify(kits, never()).searchAsync(any());
    }
}
