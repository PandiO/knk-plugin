package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.paper.commands.DuelAdminCommand;
import net.knightsandkings.knk.paper.commands.DuelCommand;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.duel.ui.DuelProposalEditor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** {@code /duel} routing and staff {@code /knk duel} (arena duels P5, KNG-219) on the real runtime. */
class DuelCommandsTest {

    @TempDir
    Path dir;
    DuelUiHarness u;
    DuelHarness h;
    DuelCommand duel;
    final Command command = mock(Command.class);

    @BeforeEach
    void setUp() {
        u = new DuelUiHarness(dir);
        h = u.h;
        VisiblePlayers visible = new VisiblePlayers(
                name -> h.players.values().stream().map(DuelTestItems.TestPlayer::player)
                        .filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null),
                id -> h.players.containsKey(id) ? h.players.get(id).player() : null,
                () -> h.players.values().stream().map(DuelTestItems.TestPlayer::player).toList());
        duel = new DuelCommand(() -> u.ui, visible);
        for (DuelTestItems.TestPlayer p : h.players.values()) {
            org.mockito.Mockito.when(p.player().canSee(org.mockito.ArgumentMatchers.any(Player.class))).thenReturn(true);
        }
    }

    private void run(DuelTestItems.TestPlayer who, String... args) {
        duel.onCommand(who.player(), command, "duel", args);
    }

    @Test
    void theDuelTreeRoutesToTheUi() {
        run(h.alice, "Bob");
        assertInstanceOf(DuelProposalEditor.Holder.class, u.screen(h.alice));
        u.clickTop(h.alice, DuelProposalEditor.CANCEL);

        run(h.alice, "bob", "quick");
        long id = h.service.offers().outgoingOf(h.alice.id()).currentOffer().offerId();
        run(h.bob, "accept", String.valueOf(id));
        h.runNextTicks();

        assertEquals(DuelPhase.WARMUP, h.service.matches().getFirst().phase());
        run(h.alice, "draw");
        assertTrue(DuelUiHarness.chat(h.alice).contains("You can offer a draw only during the fight."));
        run(h.bob, "accept");
        assertTrue(DuelUiHarness.chat(h.bob).contains("Usage: /duel accept <offer id> (click the button in chat)"));
        assertTrue(duel.onTabComplete(h.alice.player(), command, "duel", new String[] {"to"}).contains("toggle"));
        assertTrue(duel.onTabComplete(h.alice.player(), command, "duel", new String[] {"B"}).contains("Bob"));
    }

    @Test
    void staffCanListAndCancelARunningDuel() {
        Player staff = h.alice.player();
        DuelAdminCommand admin = new DuelAdminCommand(() -> h.service, () -> h.api, id -> 1,
                (sender, node) -> !node.equals(DuelAdminCommand.REVIEW_NODE), Runnable::run);
        run(h.alice, "Bob", "quick");
        run(h.bob, "accept", String.valueOf(h.service.offers().outgoingOf(h.alice.id()).currentOffer().offerId()));
        h.runNextTicks();
        DuelMatch match = h.service.matches().getFirst();
        String token = match.token().toString().substring(0, 8);

        admin.execute(staff, new String[] {"list"});
        assertTrue(DuelUiHarness.allChat(h.alice).contains(token + " #" + match.matchId() + " Alice vs Bob"),
                DuelUiHarness.allChat(h.alice));

        admin.execute(staff, new String[] {"review", token, "release"});
        assertTrue(DuelUiHarness.chat(h.alice).contains("You need knk.admin.duel.review to review duel results."));

        admin.execute(staff, new String[] {"cancel", token});
        h.runNextTicks();
        assertTrue(DuelUiHarness.chat(h.alice).contains("Duel " + token + " cancelled; both sides are refunded."));
        assertTrue(h.service.matches().isEmpty() || h.service.matches().getFirst().machine().ended());
        assertEquals(List.of("list", "cancel", "refund", "review"), admin.complete(staff, new String[] {""}));
    }

    @Test
    void duelsRoutesTheAnnouncementButtonsToP6sMenus() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        var port = new net.knightsandkings.knk.paper.duel.ui.DuelMenuPort() {
            public boolean openMatches(Player p) { calls.add("matches"); return true; }
            public boolean openMatch(Player p, java.util.UUID token) { calls.add("match " + token); return true; }
            public boolean openArena(Player p, String name) { calls.add("arena " + name); return true; }
            public void spectate(Player p, int arenaId) { calls.add("spectate " + arenaId); }
        };
        var duels = new net.knightsandkings.knk.paper.commands.DuelsCommand(() -> h.service, () -> port);
        run(h.alice, "Bob", "quick");
        run(h.bob, "accept", String.valueOf(h.service.offers().outgoingOf(h.alice.id()).currentOffer().offerId()));
        h.runNextTicks();
        java.util.UUID token = h.service.matches().getFirst().token();

        duels.onCommand(h.alice.player(), command, "duels", new String[0]);
        duels.onCommand(h.alice.player(), command, "duels", new String[] {"view", token.toString()});
        duels.onCommand(h.alice.player(), command, "duels", new String[] {"spectate", "7"});
        new net.knightsandkings.knk.paper.commands.ArenaCommand(() -> h.service, java.util.List::of,
                p -> java.util.Optional.of(h.arena), () -> port).onCommand(h.alice.player(), command, "arena", new String[0]);

        assertEquals(List.of("matches", "match " + token, "spectate 7", "arena null"), calls);
    }
}
