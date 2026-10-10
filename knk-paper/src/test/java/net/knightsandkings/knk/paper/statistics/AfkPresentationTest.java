package net.knightsandkings.knk.paper.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The AFK tab-list marker keeps the player's group color (KNG-34 smoke test finding 3): the client
 * skips scoreboard-team formatting for a custom tab-list name, so the marked name carries the
 * team's prefix, color and suffix, and leaving AFK clears the custom name again.
 */
class AfkPresentationTest {

    private static final Component MARKER = Component.text("[AFK]", NamedTextColor.GRAY);

    private static Player player(Team team) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alice");
        // Paper reports the plain name when no custom tab-list name is set.
        when(player.playerListName()).thenReturn(Component.text("Alice"));
        Scoreboard scoreboard = mock(Scoreboard.class);
        when(scoreboard.getEntryTeam("Alice")).thenReturn(team);
        when(player.getScoreboard()).thenReturn(scoreboard);
        return player;
    }

    private static Team team(NamedTextColor color, Component prefix) {
        Team team = mock(Team.class);
        when(team.hasColor()).thenReturn(color != null);
        when(team.color()).thenReturn(color);
        when(team.prefix()).thenReturn(prefix);
        when(team.suffix()).thenReturn(Component.empty());
        return team;
    }

    @Test
    void marker_KeepsTheTeamPrefixAndColor() {
        Player player = player(team(NamedTextColor.DARK_PURPLE, Component.text("§5")));
        AfkPresentation presentation = new AfkPresentation(true, "&7[AFK]");

        presentation.entered(player, true);

        ArgumentCaptor<Component> name = ArgumentCaptor.forClass(Component.class);
        verify(player).playerListName(name.capture());
        assertEquals(Component.empty().append(Component.text("§5"))
                .append(Component.text("Alice", NamedTextColor.DARK_PURPLE)).append(Component.empty())
                .append(Component.space()).append(MARKER), name.getValue());
    }

    @Test
    void marker_WithoutATeam_IsThePlainName() {
        Player player = player(null);
        new AfkPresentation(true, "&7[AFK]").entered(player, false);

        verify(player).playerListName(Component.text("Alice").append(Component.space()).append(MARKER));
    }

    @Test
    void leaving_ClearsTheCustomName_SoTheTeamColorReturns() {
        Player player = player(team(NamedTextColor.BLUE, Component.empty()));
        AfkPresentation presentation = new AfkPresentation(true, "&7[AFK]");
        presentation.entered(player, true);
        ArgumentCaptor<Component> marked = ArgumentCaptor.forClass(Component.class);
        verify(player).playerListName(marked.capture());
        when(player.playerListName()).thenReturn(marked.getValue());

        presentation.left(player);

        verify(player).playerListName(null);
    }

    @Test
    void leaving_PutsBackAnEarlierCustomName() {
        Player player = player(team(NamedTextColor.BLUE, Component.empty()));
        Component siegeName = Component.text("[Red] Alice", NamedTextColor.RED);
        when(player.playerListName()).thenReturn(siegeName);
        AfkPresentation presentation = new AfkPresentation(true, "&7[AFK]");
        presentation.entered(player, true);
        Component marked = siegeName.append(Component.space()).append(MARKER);
        verify(player).playerListName(marked);
        when(player.playerListName()).thenReturn(marked);

        presentation.left(player);

        verify(player).playerListName(siegeName);
    }

    @Test
    void leaving_LeavesANameRedrawnMeanwhileAlone() {
        Player player = player(null);
        AfkPresentation presentation = new AfkPresentation(true, "&7[AFK]");
        presentation.entered(player, true);
        when(player.playerListName()).thenReturn(Component.text("[Blue] Alice"));

        presentation.left(player);

        verify(player, never()).playerListName((Component) null);
    }

    @Test
    void noMarker_WhenTheOptionIsOff() {
        Player player = player(null);
        new AfkPresentation(false, "&7[AFK]").entered(player, true);

        verify(player, never()).playerListName(any());
    }
}
