package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Content port CP2: {@link KitMenuRow} mapping for each availability state. */
class KitMenuRowTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Map<Integer, KitMenuRow.BlueprintInfo> BLUEPRINTS = Map.of(
            1, new KitMenuRow.BlueprintInfo("Iron Helmet", "minecraft:iron_helmet"),
            2, new KitMenuRow.BlueprintInfo("Iron Chestplate", "minecraft:iron_chestplate"),
            3, new KitMenuRow.BlueprintInfo("Iron Sword", "minecraft:iron_sword"),
            4, new KitMenuRow.BlueprintInfo("Bread", "minecraft:bread"));

    private static KnkKitAvailability availability(boolean canClaim, String denial, OffsetDateTime cooldown,
                                                   boolean purchased, Integer cost, boolean premium, Integer gems) {
        return new KnkKitAvailability(7, "Starter", "A basic set of iron gear for new knights and squires alike",
                canClaim, denial, cooldown, purchased, cost, cost != null ? "Coins" : null, premium, gems);
    }

    private static KnkKit kit(Integer handId, Integer chestId) {
        return new KnkKit(7, "Starter", null, 1, chestId, null, null, null, handId,
                List.of(new KnkKitContent(3, 4, 16)), null, null, null, false, 60, null, null, false, null);
    }

    @Test
    void claimableKitShowsContentsCostAndClaimHint() {
        KitMenuRow row = KitMenuRow.of(availability(true, null, null, false, 50, false, null), kit(3, 2), BLUEPRINTS, CLOCK);

        assertEquals(7, row.getKitId());
        assertEquals("Starter", row.getName());
        assertEquals("NORMAL", row.getDisplayMode());
        assertFalse(row.getIsPurchase());
        assertNull(row.getPurchasePrompt());
        assertEquals("minecraft:iron_sword", row.getMaterial(), "hand item first");
        List<String> lore = row.getLoreLines();
        assertEquals("&7A basic set of iron gear for new", lore.get(0));
        assertTrue(lore.contains("&7- Helmet: &fIron Helmet"));
        assertTrue(lore.contains("&7- Chestplate: &fIron Chestplate"));
        assertTrue(lore.contains("&7- Hand: &fIron Sword"));
        assertTrue(lore.contains("&7Other contents: &fBread x16"));
        assertTrue(lore.contains("&7Cost: &f50 coins"));
        assertEquals("&aClick to claim", lore.get(lore.size() - 1));
        assertNull(row.getCooldownText());
    }

    @Test
    void deniedKitIsDisabledWithTheServerReasonAndACountdown() {
        OffsetDateTime until = NOW.plus(Duration.ofSeconds(250)).atOffset(ZoneOffset.UTC);
        KitMenuRow row = KitMenuRow.of(availability(false, "Kit is on cooldown", until, false, null, false, null),
                kit(null, 2), BLUEPRINTS, CLOCK);

        assertEquals("DISABLED", row.getDisplayMode());
        assertEquals("&cKit is on cooldown", row.getLoreLines().get(row.getLoreLines().size() - 1));
        assertEquals("&cAvailable again in 4m 10s", row.getCooldownText());
        assertEquals("minecraft:iron_chestplate", row.getMaterial(), "no hand item -> chestplate");
    }

    @Test
    void cooldownTextCountsDownAndDisappears() {
        OffsetDateTime until = NOW.plusSeconds(3_700).atOffset(ZoneOffset.UTC);
        KnkKitAvailability a = availability(false, "Kit is on cooldown", until, false, null, false, null);

        assertEquals("&cAvailable again in 1h 1m", KitMenuRow.of(a, null, Map.of(), CLOCK).getCooldownText());
        Clock later = Clock.fixed(NOW.plusSeconds(3_700), ZoneOffset.UTC);
        assertNull(KitMenuRow.of(a, null, Map.of(), later).getCooldownText());
        assertEquals("2d 3h", KitMenuRow.formatRemaining(Duration.ofHours(51)));
        assertEquals("1s", KitMenuRow.formatRemaining(Duration.ofMillis(200)));
    }

    @Test
    void unboughtPremiumKitIsAClickablePurchaseWithAPrompt() {
        KitMenuRow row = KitMenuRow.of(availability(false, "Not purchased", null, false, null, true, 300), null, Map.of(), CLOCK);

        assertEquals("NORMAL", row.getDisplayMode());
        assertTrue(row.getIsPurchase());
        assertEquals("Buy the kit \"Starter\" for 300 gems? Click Confirm or Cancel.", row.getPurchasePrompt());
        assertTrue(row.getLoreLines().contains("&7One-time price: &f300 gems"));
        assertEquals("CHEST", row.getMaterial(), "no contents -> CHEST");
    }

    @Test
    void boughtPremiumKitIsClaimedNormally() {
        KitMenuRow row = KitMenuRow.of(availability(true, null, null, true, null, true, 300), null, Map.of(), CLOCK);

        assertFalse(row.getIsPurchase());
        assertEquals("&aClick to claim", row.getLoreLines().get(row.getLoreLines().size() - 1));
    }

    @Test
    void rowKeyChangesWithTheVisibleStateOnly() {
        KitMenuRow claimable = KitMenuRow.of(availability(true, null, null, false, null, false, null), null, Map.of(), CLOCK);
        KitMenuRow same = KitMenuRow.of(availability(true, null, null, false, null, false, null), null, Map.of(),
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        KitMenuRow claimed = KitMenuRow.of(availability(false, "Kit is on cooldown",
                NOW.plusSeconds(60).atOffset(ZoneOffset.UTC), false, null, false, null), null, Map.of(), CLOCK);

        assertEquals(claimable.menuRowKey(), same.menuRowKey());
        assertEquals(claimable, same);
        assertNotEquals(claimable.menuRowKey(), claimed.menuRowKey());
    }

    @Test
    void emptyStateRowIsDisabled() {
        KitMenuRow none = KitMenuRow.none(CLOCK);
        assertEquals("DISABLED", none.getDisplayMode());
        assertEquals("BARRIER", none.getMaterial());
        assertFalse(none.getIsPurchase());
    }

    @Test
    void wrapBreaksOnWordsAndSkipsBlank() {
        assertEquals(List.of("aaa bbb", "ccc"), KitMenuRow.wrap("aaa bbb ccc", 7));
        assertTrue(KitMenuRow.wrap("  ", 10).isEmpty());
        assertTrue(KitMenuRow.wrap(null, 10).isEmpty());
    }
    // ===== PandiO/knk-workspace#172: "Other contents" wraps and is capped =====

    private static int visibleLength(String line) {
        return line.replaceAll("&[0-9a-fk-or]", "").length();
    }

    @Test
    void otherContentsWrapsOverSeveralLinesWithinTheMaximumWidth() {
        List<String> items = List.of("Netherite Sword", "Cleaving Netherite Axe", "Macebdu", "Crossbow", "Bow",
                "Cooked Beef x64", "Cobweb x16", "Golden Apple x8");

        List<String> lines = KitMenuRow.otherContentLines(items);

        assertTrue(lines.size() > 1, "wrapped: " + lines);
        assertTrue(lines.get(0).startsWith("&7Other contents: &fNetherite Sword,"));
        for (String line : lines) {
            assertTrue(visibleLength(line) <= KitMenuRow.CONTENTS_WIDTH, "too wide: " + line);
        }
        String joined = String.join(" ", lines.stream().map(l -> l.replaceAll("&[0-9a-fk-or]", "")).toList());
        for (String item : items) {
            assertTrue(joined.contains(item), "every item shown: " + item);
        }
        assertFalse(lines.get(lines.size() - 1).endsWith(","), "no trailing comma on the last line");
        assertFalse(joined.contains("more"));
    }

    @Test
    void aVeryLongListIsCappedWithAndNMore() {
        List<String> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            items.add("Enchanted Item Number " + i);
        }

        List<String> lines = KitMenuRow.otherContentLines(items);

        assertEquals(KitMenuRow.CONTENTS_MAX_LINES, lines.size());
        String last = lines.get(lines.size() - 1);
        assertTrue(last.matches("&7… and \\d+ more"), last);
        int shown = 0;
        for (String line : lines.subList(0, lines.size() - 1)) {
            shown += line.split(", ").length;
            assertTrue(visibleLength(line) <= KitMenuRow.CONTENTS_WIDTH, "too wide: " + line);
        }
        assertEquals(40 - shown, Integer.parseInt(last.replaceAll("&7… and (\\d+) more", "$1")));
    }

    @Test
    void anItemWiderThanTheLineIsShortened() {
        String huge = "A".repeat(60);

        List<String> lines = KitMenuRow.otherContentLines(List.of("Bread", huge));

        assertEquals(List.of("&7Other contents: &fBread,", "&f" + "A".repeat(KitMenuRow.CONTENTS_WIDTH - 1) + "…"), lines);
    }

    @Test
    void aShortListStaysOnOneLine() {
        assertEquals(List.of("&7Other contents: &fBread x16, Arrow x2"),
                KitMenuRow.otherContentLines(List.of("Bread x16", "Arrow x2")));
        assertTrue(KitMenuRow.otherContentLines(List.of()).isEmpty());
    }
}
