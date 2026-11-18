package net.knightsandkings.knk.paper.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.chat.tokens.ChatToken;

/** PandiO/knk-workspace#233: {@code #kills@} + Tab offers the players the viewer can see. */
class ChatTokenCompletionSyncTest {

    private final Player steve = player("Steve");
    private final Player alex = player("Alex");
    private final Player hiddenStaff = player("Mod");
    private final List<Player> online = new ArrayList<>();
    private final Set<String> allowed = new HashSet<>(Set.of(ChatToken.KILLS.node(), ChatToken.HAND.node()));
    private final List<Runnable> ticks = new ArrayList<>();
    private ChatTokenCompletionSync sync;

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    @BeforeEach
    void setUp() {
        when(steve.canSee(alex)).thenReturn(true);
        when(steve.canSee(hiddenStaff)).thenReturn(false); // staff/owner mode hides them from Steve
        when(alex.canSee(any(Player.class))).thenReturn(true);
        when(hiddenStaff.canSee(any(Player.class))).thenReturn(true);
        online.addAll(List.of(steve, alex, hiddenStaff));
        sync = new ChatTokenCompletionSync((player, node) -> allowed.contains(node), () -> online, ticks::add);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> added(Player player) {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(player).addCustomChatCompletions(captor.capture());
        return captor.getValue();
    }

    @Test
    void eachViewerGetsTheTokensWithThePlayersTheyCanSee_NotThemselves() {
        sync.refreshAll();

        assertEquals(List.of("#hand", "#kills", "#kills@Alex"), List.copyOf(added(steve)));
        assertEquals(List.of("#hand", "#kills", "#kills@Mod", "#kills@Steve"), List.copyOf(added(alex)));
    }

    @Test
    void onlyTheDifferenceIsSent_AndNothingWhenNothingChanged() {
        sync.refreshAll();
        sync.refreshAll(); // unchanged: no second call (verify above would fail on two calls)
        assertEquals(3, added(steve).size());

        online.remove(alex);
        sync.refreshAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> removed = ArgumentCaptor.forClass(Collection.class);
        verify(steve).removeCustomChatCompletions(removed.capture());
        assertEquals(List.of("#kills@Alex"), List.copyOf(removed.getValue()));
    }

    @Test
    void aPlayerWithoutAnyTokenNodeGetsNothing() {
        allowed.clear();

        sync.refreshAll();

        verify(steve, never()).addCustomChatCompletions(any());
        assertTrue(ticks.isEmpty());
    }
}
