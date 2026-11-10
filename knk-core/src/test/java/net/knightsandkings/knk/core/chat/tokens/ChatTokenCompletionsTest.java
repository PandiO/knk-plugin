package net.knightsandkings.knk.core.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#233: Tab after {@code #kills@} offers the players the viewer can see. */
class ChatTokenCompletionsTest {

    @Test
    void statisticsTokensGetEveryVisiblePlayer_OtherTokensOnlyThemselves() {
        var completions = ChatTokenCompletions.forViewer(EnumSet.of(ChatToken.HAND, ChatToken.KILLS), List.of("Alex", "Bob"));

        assertEquals(List.of("#hand", "#kills", "#kills@Alex", "#kills@Bob"), completions);
    }

    @Test
    void tokensWithoutPermissionAreLeftOut() {
        var completions = ChatTokenCompletions.forViewer(EnumSet.of(ChatToken.XP), List.of("Alex"));

        assertEquals(List.of("#xp", "#xp@Alex"), completions);
        assertTrue(ChatTokenCompletions.forViewer(EnumSet.noneOf(ChatToken.class), List.of("Alex")).isEmpty());
    }

    @Test
    void everyCompletionIsAToken() {
        var all = ChatTokenCompletions.forViewer(EnumSet.allOf(ChatToken.class), List.of("Alex_2"));

        assertTrue(all.stream().allMatch(c -> ChatTokenParser.find(c).size() == 1), all.toString());
        assertEquals(14 + 11, all.size());
    }
}
