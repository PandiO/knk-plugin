package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lootboxes Phase 3: {@code /lootbox} is player-only, gates odds on its node, and has no admin subcommands. */
class LootboxCommandTest {

    static final KnkLootboxRuntimeConfig CONFIG = new KnkLootboxRuntimeConfig(true, 15, 10, 5, 6, null, null, null,
            List.of(new KnkLootboxType(3, "Weapons Lootbox", 1, "Weapons", "minecraft:iron_sword", 10, 1, 5, null, null)),
            List.of(), List.of());

    private final LootboxesQueryApi queryApi = mock(LootboxesQueryApi.class);
    private final Player player = mock(Player.class);
    private boolean allowed = true;
    private final LootboxCommand command = new LootboxCommand(() -> CONFIG, queryApi, (p, node) -> allowed, Runnable::run);

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "lootbox", args);
    }

    @Test
    void console_isRefused() {
        CommandSender console = mock(CommandSender.class);
        run(console, "odds", "weapons");
        verify(console).sendMessage(contains("Only players"));
        verify(queryApi, never()).getOdds(anyInt(), isNull());
    }

    @Test
    void adminSubcommands_areNotHere() {
        for (String admin : List.of("spawn", "give", "despawn", "area", "reload")) {
            run(player, admin, "weapons");
        }
        verify(player, atLeastOnce()).sendMessage(contains("/lootbox odds"));
        verify(queryApi, never()).getOdds(anyInt(), isNull());
    }

    @Test
    void odds_needsItsNode() {
        allowed = false;
        run(player, "odds", "weapons");
        verify(player).sendMessage(contains("permission"));
        verify(queryApi, never()).getOdds(anyInt(), isNull());
    }

    @Test
    void odds_unknownCategory_makesNoCall() {
        run(player, "odds", "boots");
        verify(player).sendMessage(contains("No lootbox for"));
        verify(queryApi, never()).getOdds(anyInt(), isNull());
    }

    @Test
    void odds_starsOutsideTheTypesRange_areRefused() {
        run(player, "odds", "weapons", "7");
        verify(player).sendMessage(contains("★1-★5"));
    }

    @Test
    void odds_asksTheApiAndPrints() {
        KnkLootboxOdds odds = new KnkLootboxOdds(3, "Weapons Lootbox", 4, 99.55,
                List.of(new KnkLootboxOdds.Grade("Legendary", 5, 18.75, 1)),
                List.of(new KnkLootboxOdds.Item("&aGolemheart Sword", 5, 18.66)),
                List.of(new KnkLootboxOdds.Special("&cFlaming Samurai", 0.05)));
        when(queryApi.getOdds(3, 4)).thenReturn(CompletableFuture.completedFuture(odds));

        run(player, "odds", "Weapons", "4");

        verify(queryApi).getOdds(eq(3), eq(4));
        verify(player).sendMessage(contains("Golemheart Sword"));
        verify(player).sendMessage(contains("0.050%"));
    }

    @Test
    void tabComplete_offersCategories() {
        assertEquals(List.of("weapons"), command.onTabComplete(player, mock(Command.class), "lootbox", new String[]{"odds", "we"}));
        assertEquals(List.of("odds"), command.onTabComplete(player, mock(Command.class), "lootbox", new String[]{"o"}));
    }
}
