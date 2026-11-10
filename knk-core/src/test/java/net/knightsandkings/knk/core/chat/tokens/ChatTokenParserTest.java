package net.knightsandkings.knk.core.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#233: which text is a token. */
class ChatTokenParserTest {

    private static List<String> keys(String text) {
        return ChatTokenParser.find(text).stream().map(ChatTokenRef::key).toList();
    }

    @Test
    void findsSeveralTokensInOneMessage_InOrder() {
        assertEquals(List.of("hand@", "kills@", "stats@steve"), keys("look at #hand, I have #kills! and #stats@Steve."));
    }

    @Test
    void namesAreCaseInsensitive_AndTheRawTextIsKept() {
        var ref = ChatTokenParser.find("#KiLLs@Steve_2").get(0);
        assertEquals(ChatToken.KILLS, ref.token());
        assertEquals("Steve_2", ref.target());
        assertEquals("#KiLLs@Steve_2", ref.raw());
    }

    @Test
    void unknownTokensAndGluedTextStayText() {
        assertTrue(keys("#lol #handy abc#hand ##hand &#FF0000 #FF0000 C#").isEmpty());
    }

    @Test
    void aBrokenTargetIsNotTheSendersOwnValue() {
        // "#kills@" and a name over 16 characters must not fall back to the sender's kills.
        assertTrue(keys("#kills@ #kills@ThisNameIsWayTooLong1").isEmpty());
    }

    @Test
    void onlyStatisticsTokensTakeATarget() {
        assertTrue(keys("#hand@Steve #location@Steve #tokens@Steve").isEmpty());
        assertEquals(List.of("wins@bob"), keys("#wins@Bob"));
    }

    @Test
    void punctuationAroundATokenIsFine() {
        assertEquals(List.of("hand@", "kd@"), keys("(#hand) \"#kd\""));
    }

    @Test
    void aTokenIsAboutTheSenderWithoutATarget_OrWithTheirOwnName() {
        var own = ChatTokenParser.find("#kills").get(0);
        var named = ChatTokenParser.find("#kills@steve").get(0);
        var other = ChatTokenParser.find("#kills@Alex").get(0);
        assertNull(own.target());
        assertTrue(own.isAbout("Steve"));
        assertTrue(named.isAbout("Steve"));
        assertTrue(!other.isAbout("Steve"));
    }

    @Test
    void everyTokenHasItsOwnNode_AndTheBypassIsOutsideTheWildcard() {
        assertEquals("knk.chat.token.hand", ChatToken.HAND.node());
        assertTrue(!ChatToken.BYPASS_NODE.startsWith(ChatToken.NODE_PREFIX));
        assertEquals(14, ChatToken.all().size());
    }
}
