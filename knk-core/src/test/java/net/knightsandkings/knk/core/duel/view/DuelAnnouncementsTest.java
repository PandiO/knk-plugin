package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.view.DuelAnnouncements.Payout;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.NOTCH;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.PANDI;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.match;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena duels P6 (DESIGN §9.1): the announcer's spam cooldown, the arena toggles and the lines. */
class DuelAnnouncementsTest {

    private final DuelAnnouncements.Templates t = DuelAnnouncements.Templates.defaults();

    @Test
    void oneWarmupBroadcastPerPlayerPerCooldown() {
        DuelAnnouncements.Cooldown cooldown = new DuelAnnouncements.Cooldown();
        UUID steve = UUID.randomUUID();
        long now = 1_000;

        assertTrue(cooldown.tryBroadcast(PANDI, NOTCH, now, 120));
        assertFalse(cooldown.tryBroadcast(PANDI, NOTCH, now + 20 * 60, 120), "the same pair a minute later");
        assertFalse(cooldown.tryBroadcast(PANDI, steve, now + 20 * 60, 120), "one of them still on cooldown");
        assertFalse(cooldown.isOnCooldown(steve, now + 20 * 60, 120), "a refused broadcast stamps nobody");
        assertTrue(cooldown.tryBroadcast(steve, UUID.randomUUID(), now + 20 * 60, 120), "two fresh players");
        assertTrue(cooldown.tryBroadcast(PANDI, NOTCH, now + 20 * 120, 120), "the cooldown is over");
        assertTrue(cooldown.isOnCooldown(NOTCH, now + 20 * 120 + 1, 120), "and starts again");
    }

    @Test
    void aZeroCooldownNeverLimits() {
        DuelAnnouncements.Cooldown cooldown = new DuelAnnouncements.Cooldown();
        assertTrue(cooldown.tryBroadcast(PANDI, NOTCH, 5, 0));
        assertTrue(cooldown.tryBroadcast(PANDI, NOTCH, 5, 0));
    }

    @Test
    void theArenaTogglesDecideWhatIsBroadcast() {
        assertTrue(DuelAnnouncements.arenaAnnounces(true, true, true));
        assertTrue(DuelAnnouncements.arenaAnnounces(true, false, false), "staked duels need only AnnounceMatches");
        assertFalse(DuelAnnouncements.arenaAnnounces(true, false, true), "friendly duels need AnnounceFriendly too");
        assertFalse(DuelAnnouncements.arenaAnnounces(false, true, false), "AnnounceMatches off silences the arena");
        assertFalse(DuelAnnouncements.arenaAnnounces(false, true, true));
    }

    @Test
    void theWarmupLineNamesTheArenaThePairTheGearAndThePot() {
        assertEquals("&6⚔ Duel at &eThe Arena&6: &ePandi &6vs &eNotch &7- own gear - stakes: &f700 coins + 2 items",
                DuelAnnouncements.warmup(t, match().build()));
        assertEquals("&6⚔ Duel at &eThe Arena&6: &ePandi &6vs &eNotch &7- kit duel (Knight) - stakes: &fno stakes",
                DuelAnnouncements.warmup(t, match().gear(GearMode.FIXED_KIT, "Knight", "Knight").friendly().build()));
        assertTrue(DuelAnnouncements.warmup(t, match().gear(GearMode.OWN_GEAR_DROP, null, null).build())
                .contains("OWN GEAR, LOSER DROPS ALL"), "drop mode in capitals");
    }

    @Test
    void theResultSaysWhoWonInWhatTimeAndWhatTheyWonAfterTheFee() {
        var m = match().ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 102).build();
        assertEquals("&6⚔ &ePandi &6defeated &eNotch &6in &f1:42 and won &f665 coins + 2 items&6.",
                DuelAnnouncements.result(t, m, Payout.PAID));
    }

    @Test
    void aHeldResultSaysPendingReview() {
        var m = match().ended(DuelEndReason.WIN, DuelSide.OPPONENT, 30).held().build();
        String line = DuelAnnouncements.result(t, m, Payout.HELD);
        assertTrue(line.startsWith("&6⚔ &eNotch &6defeated &ePandi"), line);
        assertTrue(line.contains("(pending review)"), line);
        assertTrue(DuelAnnouncements.result(t, m, Payout.PENDING).contains("(payout follows)"));
        assertFalse(DuelAnnouncements.result(t, m, Payout.NONE).contains("won"), "a refunded result names no winnings");
    }

    @Test
    void aFriendlyResultNamesNoWinnings_AndAForfeitSaysSo() {
        var friendly = match().friendly().ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 61).build();
        assertEquals("&6⚔ &ePandi &6defeated &eNotch &6in &f1:01&6.", DuelAnnouncements.result(t, friendly, Payout.PAID));

        var forfeit = match().ended(DuelEndReason.FORFEIT, DuelSide.OPPONENT, 10).build();
        assertTrue(DuelAnnouncements.result(t, forfeit, Payout.PAID).startsWith("&6⚔ &eNotch &6won by forfeit against &ePandi"));
    }

    @Test
    void dropModeSpoilsAreNamed() {
        // No stakes, but drop mode moves the loser's inventory: value-moving, not friendly.
        var drop = match().gear(GearMode.OWN_GEAR_DROP, null, null)
                .sides(DuelViewFixtures.pandi(null, 0), DuelViewFixtures.notch(null, 0))
                .ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 5).build();
        assertTrue(DuelAnnouncements.result(t, drop, Payout.PAID).contains(" and won the loser's inventory"));
        var staked = match().gear(GearMode.OWN_GEAR_DROP, null, null).ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 5).build();
        assertTrue(DuelAnnouncements.result(t, staked, Payout.PAID).contains("665 coins + 2 items &6+ the loser's inventory"));
    }

    @Test
    void drawsCancelsAndTheDeathLine() {
        var draw = match().ended(DuelEndReason.TIME_LIMIT, null, 300).build();
        assertEquals("&6⚔ &ePandi &6and &eNotch &6drew at &eThe Arena &7(time limit)&6.", DuelAnnouncements.draw(t, draw));
        assertNull(DuelAnnouncements.result(t, draw, Payout.PAID));
        assertNull(DuelAnnouncements.death(t, draw));

        var cancelled = match().ended(DuelEndReason.NO_BATTLEGROUND, null, 0).build();
        assertEquals("&7The duel &fPandi &7vs &fNotch &7was cancelled: no battleground came free in time",
                DuelAnnouncements.cancel(t, cancelled));

        var win = match().ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 5).build();
        assertEquals("&cNotch &7was defeated by &cPandi&7.", DuelAnnouncements.death(t, win));
    }

    @Test
    void configuredTemplatesReplaceTheDefaults_BlankKeepsThem() {
        var custom = new DuelAnnouncements.Templates("{challenger}+{opponent}@{arena}", " ", null, null, null, null);
        assertEquals("Pandi+Notch@The Arena", DuelAnnouncements.warmup(custom, match().build()));
        assertEquals(DuelAnnouncements.DEFAULT_RESULT, custom.result());
    }
}
