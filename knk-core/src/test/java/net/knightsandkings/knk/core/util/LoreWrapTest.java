package net.knightsandkings.knk.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#212: the shared lore wrap (and its inverse for the item scan, #40). */
class LoreWrapTest {

    @AfterEach
    void restoreDefaultWidth() {
        LoreWrap.setMaxWidth(LoreWrap.DEFAULT_MAX_WIDTH);
    }

    // ===== measuring =====

    @Test
    void visibleLengthIgnoresLegacyAndHexCodes() {
        assertEquals(5, LoreWrap.visibleLength("§l§bHello"));
        assertEquals(5, LoreWrap.visibleLength("&7Hel&clo"));
        assertEquals(2, LoreWrap.visibleLength("§x§a§b§c§d§e§fhi"));
        assertEquals(9, LoreWrap.visibleLength("§l§bGrade: ★★"));
        assertEquals("Grade: ★★", LoreWrap.stripCodes("§l§bGrade: §x§a§b§c§d§e§f★★"));
        assertEquals(0, LoreWrap.visibleLength(null));
    }

    @Test
    void anAmpersandThatIsNoCodeIsText() {
        assertEquals(13, LoreWrap.visibleLength("Salt & Pepper"));
        assertEquals(List.of("Salt & Pepper"), LoreWrap.wrap("Salt & Pepper", 20));
    }

    // ===== wrapping =====

    @Test
    void aLineThatFitsIsReturnedUnchanged() {
        String line = "§7Short line";
        assertSame(line, LoreWrap.wrap(line, 40).get(0));
        assertEquals(List.of(""), LoreWrap.wrap("", 40), "blank spacer lines stay");
        assertEquals(List.of(), LoreWrap.wrap(null, 40));
    }

    @Test
    void breaksAtWordBoundariesWithinTheWidth() {
        assertEquals(List.of("aaa bbb", "ccc"), LoreWrap.wrap("aaa bbb ccc", 7));
        List<String> lines = LoreWrap.wrap("You need the rank Knight or higher to claim this kit", 20);
        assertEquals(List.of("You need the rank", "Knight or higher to", "claim this kit"), lines);
        lines.forEach(line -> assertTrue(LoreWrap.visibleLength(line) <= 20, line));
    }

    @Test
    void codesDoNotCountTowardsTheWidth() {
        // 18 visible characters, 26 with codes: fits in 20.
        String line = "§c§lRequires §fKnight";
        assertEquals(List.of(line), LoreWrap.wrap(line, 20));
    }

    @Test
    void continuationLinesKeepTheColourAndFormats() {
        assertEquals(List.of("&cRequires the rank", "&cKnight to claim"), LoreWrap.wrap("&cRequires the rank Knight to claim", 18));
        assertEquals(List.of("§7§lBold gray words", "§7§lcontinue here"), LoreWrap.wrap("§7§lBold gray words continue here", 17));
        // A colour change mid-line is what the next line continues in.
        assertEquals(List.of("§7Gray then §cred", "§cwords"), LoreWrap.wrap("§7Gray then §cred words", 16));
    }

    @Test
    void hexColoursAreCarriedToo() {
        String hex = "§x§a§b§c§d§e§f";
        assertEquals(List.of(hex + "one two", hex + "three"), LoreWrap.wrap(hex + "one two three", 7));
    }

    @Test
    void aResetMidLineIsCarriedAsNoCodes() {
        assertEquals(List.of("§cred §rplain", "words"), LoreWrap.wrap("§cred §rplain words", 10));
    }

    @Test
    void aWordLongerThanTheWidthIsHardBroken() {
        assertEquals(List.of("see", "§7abcdefghij", "§7klmno end"), LoreWrap.wrap("see §7abcdefghijklmno end", 10));
        assertEquals(List.of("abcde", "fghij"), LoreWrap.wrap("abcdefghij", 5));
    }

    @Test
    void aWordThatFitsIsNeverSplit() {
        assertEquals(List.of("tiny", "exactlyten"), LoreWrap.wrap("tiny exactlyten", 10));
    }

    @Test
    void wrappingTwiceChangesNothing() {
        List<String> once = LoreWrap.wrapAll(List.of("§8Forged in the last fire of a fallen dojo, where the steel sang.", "", "§7Origin: Cinix (Town)"), 40);
        assertEquals(once, LoreWrap.wrapAll(once, 40));
        assertEquals("", once.get(2), "the spacer stays");
    }

    @Test
    void theConfiguredWidthIsUsedAndHasAFloor() {
        String line = "one two three four five six seven eight nine ten";
        assertEquals(LoreWrap.wrap(line, 40), LoreWrap.wrap(line));
        LoreWrap.setMaxWidth(20);
        assertEquals(20, LoreWrap.maxWidth());
        assertEquals(LoreWrap.wrap(line, 20), LoreWrap.wrap(line));
        LoreWrap.setMaxWidth(2);
        assertEquals(LoreWrap.MIN_WIDTH, LoreWrap.maxWidth());
    }

    // ===== truncate / packItems (#172's "Other contents") =====

    @Test
    void truncateCutsVisibleCharactersWithAnEllipsis() {
        assertEquals("abcd…", LoreWrap.truncate("abcdefgh", 5));
        assertEquals("§7abcd…", LoreWrap.truncate("§7abcdefgh", 5));
        assertEquals("abc", LoreWrap.truncate("abc", 5));
    }

    @Test
    void packItemsKeepsItemsWholeAndRoomForTheTrailingComma() {
        // width 12, label of 4 on the first line: "Bread, Fish," = 12 is too wide with the label.
        assertEquals(List.of(List.of("Bread"), List.of("Fish", "Cake")),
                LoreWrap.packItems(List.of("Bread", "Fish", "Cake"), 2, 1, 12, 4));
        assertTrue(LoreWrap.packItems(List.of(), 2, 1, 12, 0).isEmpty());
    }

    // ===== unwrap (#40: a scan captures the description as authored) =====

    @Test
    void unwrapRestoresWhatWrapMade() {
        List<String> authored = List.of(
                "§8Forged in the last fire of a fallen dojo, where the steel sang.",
                "§8Its edge never cools.",
                "§7Gray then §cred words that go on for quite a while longer",
                "see §7abcdefghijklmnopqrstuvwxyzabcdefghijklmnopq end",
                "Plain lore without any colour that is also long enough to wrap");
        List<String> wrapped = LoreWrap.wrapAll(authored, 40);
        assertTrue(wrapped.size() > authored.size(), "something wrapped: " + wrapped);
        assertEquals(authored, LoreWrap.unwrap(wrapped, 40));
    }

    @Test
    void unwrapLeavesSeparateAuthoredLinesAlone() {
        List<String> lines = List.of("§8Short line.", "§8Another one.", "§7A different colour", "", "§7after a spacer");
        assertEquals(lines, LoreWrap.unwrap(lines, 40));
    }

    @Test
    void unwrapDoesNotJoinALineThatLostTheCarriedColour() {
        // B would not have fit after A, but a continuation of a gray line starts gray; B has no colour.
        List<String> lines = List.of("§7" + "x".repeat(35), "Plainword here");
        assertEquals(lines, LoreWrap.unwrap(lines, 40));
    }

    @Test
    void anAuthoredPairThatLooksWrappedIsJoinedButRewrapsTheSame() {
        List<String> authored = List.of("§8" + "word ".repeat(7) + "end.", "§8Following line here.");
        List<String> unwrapped = LoreWrap.unwrap(authored, 40);
        assertEquals(1, unwrapped.size());
        assertEquals(authored, LoreWrap.wrapAll(unwrapped, 40), "the item looks the same after a rebuild");
    }

    @Test
    void unwrapToleratesColourCodesTheServerRewrote() {
        // A continuation that kept its colour but with a code the server wrote differently (§8 → &8 here).
        assertEquals(List.of("§8Forged in the last fire of a fallen dojo, where"),
                LoreWrap.unwrap(List.of("§8Forged in the last fire of a fallen", "&8dojo, where"), 40));
    }
}
