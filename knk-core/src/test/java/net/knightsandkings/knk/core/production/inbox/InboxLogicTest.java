package net.knightsandkings.knk.core.production.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#684: the reward inbox's words, paging and keys (production DESIGN § 4.6, D7, A15). */
class InboxLogicTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static InboxView.Entry entry(int blueprintId, long amount, Instant expires, long expiring) {
        return new InboxView.Entry(blueprintId, "Bread", null, null, amount, expires, expiring);
    }

    @Test
    void items_singularAndPlural_withThousands() {
        assertEquals("1 item", InboxText.items(1));
        assertEquals("12 items", InboxText.items(12));
        assertEquals("1,024 items", InboxText.items(1024));
    }

    @Test
    void fill_withAndWithoutCap() {
        assertEquals("120 of 2,304 (5%)", InboxText.fill(120, 2304));
        assertEquals("120", InboxText.fill(120, 0));
    }

    @Test
    void expiresIn_daysHoursAndNow() {
        assertEquals("in 7 days", InboxText.expiresIn(NOW.plus(Duration.ofDays(7)).plusSeconds(5), NOW));
        assertEquals("in 1 day", InboxText.expiresIn(NOW.plus(Duration.ofHours(30)), NOW));
        assertEquals("in 5 hours", InboxText.expiresIn(NOW.plus(Duration.ofHours(5)).plusSeconds(1), NOW));
        assertEquals("within the hour", InboxText.expiresIn(NOW.plusSeconds(600), NOW));
        assertEquals("now", InboxText.expiresIn(NOW.minusSeconds(1), NOW));
        assertEquals("later", InboxText.expiresIn(null, NOW));
    }

    @Test
    void delivered_lossFirst_thenInbox_thenWarning() {
        List<String> lines = InboxText.delivered(10, 3, 95);
        assertEquals(3, lines.size());
        assertEquals("Your reward inbox is full: 3 items were lost.", lines.get(0));
        assertEquals("10 items didn't fit and went to your reward inbox (/inbox).", lines.get(1));
        assertTrue(lines.get(2).startsWith("Your reward inbox is 95% full."));
        assertEquals(List.of("1 item didn't fit and went to your reward inbox (/inbox)."), InboxText.delivered(1, 0, null));
        assertTrue(InboxText.delivered(0, 0, null).isEmpty());
    }

    @Test
    void notice_deliveredReminderExpired() {
        InboxNotice delivered = new InboxNotice("Delivered", 5L, "Kit", 4, 2, 80, 81, 2304, 0, null);
        assertEquals(List.of("Your reward inbox is full: 2 items were lost.", "4 items arrived in your reward inbox (/inbox).",
                InboxText.warning(80)), InboxText.notice(delivered, NOW));
        InboxNotice reminder = new InboxNotice("ExpiryReminder", null, null, 0, 0, null, 10, 2304, 12,
                NOW.plus(Duration.ofDays(7)).plusSeconds(30));
        assertEquals(List.of("12 items in your reward inbox expire in 7 days. Claim them with /inbox."),
                InboxText.notice(reminder, NOW));
        InboxNotice expired = new InboxNotice("Expired", null, null, 0, 1, null, 0, 2304, 0, null);
        assertEquals(List.of("1 item in your reward inbox expired and is gone."), InboxText.notice(expired, NOW));
        assertTrue(InboxText.notice(new InboxNotice("Other", null, null, 0, 0, null, 0, 0, 0, null), NOW).isEmpty());
        assertTrue(InboxText.notice(null, NOW).isEmpty());
    }

    @Test
    void joinSummary_countsSoonExpiryAndWarning_emptyForEmptyInbox() {
        InboxView view = new InboxView(1, 9, 100, 2304, 96, 95, null, false, List.of(
                entry(1, 30, NOW.plus(Duration.ofDays(2)).plusSeconds(9), 10), entry(2, 10, NOW.plus(Duration.ofDays(20)), 10)));
        List<String> lines = InboxText.joinSummary(view, NOW);
        assertEquals("You have 40 items in your reward inbox (/inbox).", lines.get(0));
        assertEquals("Some of them expire in 2 days.", lines.get(1));
        assertEquals(InboxText.warning(95), lines.get(2));
        InboxView later = new InboxView(1, 9, 10, 2304, 0, null, null, false,
                List.of(entry(1, 10, NOW.plus(Duration.ofDays(20)), 10)));
        assertEquals(1, InboxText.joinSummary(later, NOW).size());
        assertTrue(InboxText.joinSummary(InboxView.empty(1), NOW).isEmpty());
    }

    @Test
    void view_totalAndNextExpiry_entryNames() {
        Instant soon = NOW.plusSeconds(60);
        InboxView view = new InboxView(1, 9, 0, 0, 0, null, null, false,
                List.of(entry(1, 3, NOW.plusSeconds(600), 3), entry(2, 4, soon, 1), entry(3, 5, null, 0)));
        assertEquals(12, view.totalAmount());
        assertEquals(soon, view.nextExpiry());
        assertNull(InboxView.empty(1).nextExpiry());
        assertEquals("Item #4", new InboxView.Entry(4, null, null, null, 1, null, 0).displayName());
        assertEquals("Excalibur", new InboxView.Entry(4, "Sword", 77L, "Excalibur", 1, null, 0).displayName());
        assertTrue(new InboxView.Entry(4, "Sword", 77L, null, 1, null, 0).unique());
    }

    @Test
    void entryExpiry_usesTheExpiringAmount() {
        assertEquals("10 expire in 2 days", InboxText.entryExpiry(entry(1, 30, NOW.plus(Duration.ofDays(2)).plusSeconds(1), 10), NOW));
        assertEquals("1 expires in 5 hours", InboxText.entryExpiry(entry(1, 1, NOW.plus(Duration.ofHours(5)).plusSeconds(1), 0), NOW));
        assertNull(InboxText.entryExpiry(entry(1, 1, null, 0), NOW));
    }

    @Test
    void claimShort_namesTheReason() {
        assertEquals("the inbox has less than that", InboxText.claimShort(List.of("NotEnoughStock")));
        assertEquals("your inbox is locked right now", InboxText.claimShort(List.of("StorageLocked")));
        assertEquals("the inbox has less than that", InboxText.claimShort(List.of()));
    }

    @Test
    void layout_pagesOf45() {
        assertEquals(1, InboxLayout.pageCount(0));
        assertEquals(1, InboxLayout.pageCount(45));
        assertEquals(2, InboxLayout.pageCount(46));
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(i);
        }
        assertEquals(45, InboxLayout.page(entries, 1).size());
        assertEquals(List.of(45, 46, 47, 48, 49), InboxLayout.page(entries, 2));
        assertEquals(List.of(45, 46, 47, 48, 49), InboxLayout.page(entries, 9));
        assertEquals(0, InboxLayout.entryIndex(0));
        assertEquals(44, InboxLayout.entryIndex(44));
        assertEquals(-1, InboxLayout.entryIndex(InboxLayout.INFO_SLOT));
        assertTrue(InboxLayout.page(List.of(), 1).isEmpty());
    }

    @Test
    void keys_matchTheApiRule() {
        assertEquals("kit-claim-12", RewardKeys.kitClaim(12));
        assertEquals("lootbox-claim-7", RewardKeys.lootboxClaim(7));
        assertEquals("kit-first-join-u3", RewardKeys.firstJoin(3));
        String grant = RewardKeys.kitGrant(5, 3);
        assertTrue(grant.startsWith("kit-5-u3-"), grant);
        assertTrue(RewardKeys.valid(grant));
        assertTrue(RewardKeys.valid(RewardKeys.claim(Integer.MAX_VALUE)));
        assertFalse(RewardKeys.kitGrant(5, 3).equals(RewardKeys.kitGrant(5, 3)), "each grant its own key");
        assertFalse(RewardKeys.valid("has space"));
        assertFalse(RewardKeys.valid(""));
        assertFalse(RewardKeys.valid("x".repeat(81)));
        assertEquals("has-space-and-", RewardKeys.sanitize("has space and/"));
        assertEquals(80, RewardKeys.sanitize("y".repeat(100)).length());
        assertTrue(RewardKeys.valid(RewardKeys.sanitize(null)));
    }

    @Test
    void request_totals() {
        DeliveryRequest request = new DeliveryRequest(1, DeliveryRequest.Reason.KIT, "Kit", "Starter", null, "k",
                List.of(DeliveryRequest.Line.of(3, 10), DeliveryRequest.Line.unique(99)));
        assertEquals(11, request.totalAmount());
        assertEquals("Lootbox", DeliveryRequest.Reason.LOOTBOX.apiName());
    }
}
