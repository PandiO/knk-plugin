package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.NOTCH;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.PANDI;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.area;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.arena;
import static net.knightsandkings.knk.core.duel.view.DuelViewFixtures.match;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena duels P6 (DESIGN §9.2–§9.3): the pure menu views, the sidebar and the boss bar. */
class DuelViewsTest {

    // ===== duel_matches =====

    @Test
    void everyListedMatchIsTwoHeads_ChallengerFirst_AndBothOpenTheSameMatch() {
        var warmup = match().token("one").build();
        var travelling = match().token("two").phase(DuelPhase.TRAVELLING, 0, 0).build();
        var fight = match().token("three").phase(DuelPhase.FIGHT, 192, 300).battleground("Pit").build();

        List<DuelMatchRowView> rows = DuelMatchRowView.rows(List.of(warmup, travelling, fight));

        assertEquals(4, rows.size(), "TRAVELLING isn't listed (WARMUP → ENDING)");
        assertEquals(List.of("Pandi", "Notch", "Pandi", "Notch"), rows.stream().map(DuelMatchRowView::getSkullOwner).toList());
        assertEquals(rows.get(0).getToken(), rows.get(1).getToken());
        assertEquals("&ePandi &7vs &eNotch", rows.get(0).getName());
        assertEquals("&eNotch &7vs &ePandi", rows.get(1).getName());
        assertTrue(rows.get(2).getLines().contains("&7The Arena &8- &7Pit"), rows.get(2).getLines().toString());
        assertTrue(rows.get(2).getLines().contains("&7Stage: &aFighting"));
        assertTrue(rows.get(2).getLines().contains("&7Timer: &f3:12 left"));
        assertTrue(rows.get(2).getLines().contains("&7Stakes: &f700 coins + 2 items"));
    }

    @Test
    void aWaitingMatchShowsItsPlaceInLine() {
        var waiting = match().waiting(2).build();
        List<String> lines = DuelMatchRowView.rows(List.of(waiting)).getFirst().getLines();
        assertTrue(lines.contains("&7Stage: &aWaiting for a battleground"));
        assertTrue(lines.contains("&7Timer: &f#2 in line"));
    }

    // ===== duel_match_detail =====

    @Test
    void theDetailTileShowsStageTimerGearAndPot_AndAnEndedMatchIsOver() {
        var view = DuelMatchDetailView.of(match().gear(GearMode.FIXED_KIT, "Knight", "Knight").build());
        assertEquals("true", view.getKnown());
        assertEquals("7", view.getArenaId());
        assertEquals("&6Pandi &7vs &6Notch", view.getVsTitle());
        assertEquals(List.of("&7Arena: &fThe Arena", "&7Stage: &aWarmup", "&7Timer: &f0:23 left",
                "&7Gear: &fkit duel (Knight)", "&7Pot: &f700 coins + 2 items", "&7Fee: &f5% &7of the coins"), view.getVsLines());

        var gone = DuelMatchDetailView.of(match().phase(DuelPhase.CLOSED, 0, 0).build());
        assertEquals("false", gone.getKnown());
        assertEquals("&cThis duel is over", gone.getVsTitle());
        assertEquals("BARRIER", gone.getVsMaterial());
        assertEquals("false", DuelMatchDetailView.of(null).getKnown());
    }

    @Test
    void aSideCardShowsKitStakeAndOnlyTheStatsTheApiReturned() {
        var m = match().gear(GearMode.KIT_CHOICE, "Knight", "Archer").build();
        var profile = new PlayerStatistics.Profile("Squire", 1250, 0, 0, null, 3_600, 0);
        var visible = new PlayerStatistics(2, "Notch", "lifetime", null, null, "UTC", "public", profile, List.of(
                new PlayerStatistics.Metric("wins", "wins", null, null, "sum", List.of(new PlayerStatistics.ContextValue("duel", 4))),
                new PlayerStatistics.Metric("losses", "losses", null, null, "sum", List.of(new PlayerStatistics.ContextValue("duel", 1))),
                new PlayerStatistics.Metric("draws", "draws", null, null, "sum", List.of(new PlayerStatistics.ContextValue("duel", 0))),
                new PlayerStatistics.Metric("pvp_kills", "pvp_kills", 37.0, null, "sum", List.of())), null, null);

        List<String> lines = DuelSideCardView.of(m, DuelSide.OPPONENT, visible).getLines();
        assertEquals("&7Opponent", lines.getFirst());
        assertTrue(lines.contains("&7Kit: &fArcher"));
        assertTrue(lines.contains("&7Stake: &f350 coins + 1 item"));
        assertTrue(lines.contains("&7Title: &fSquire"), lines.toString());
        assertTrue(lines.contains("&7Experience: &f1,250"));
        assertTrue(lines.contains("&7Duels: &a4W &c1L &f0D"));
        assertTrue(lines.contains("&7PvP kills: &f37"));

        // The viewer may see the duel results (public by default, D17) but not the kills.
        var hidden = new PlayerStatistics(2, "Notch", "lifetime", null, null, "UTC", "public", profile, List.of(
                new PlayerStatistics.Metric("wins", "wins", null, null, "sum", List.of(new PlayerStatistics.ContextValue("duel", 4)))),
                null, null);
        List<String> partial = DuelSideCardView.of(m, DuelSide.OPPONENT, hidden).getLines();
        assertTrue(partial.contains("&7Duels: &a4W &c?L &f?D"));
        assertFalse(partial.stream().anyMatch(l -> l.contains("PvP kills")));

        assertTrue(DuelSideCardView.loading(m, DuelSide.CHALLENGER).getLines().contains("&8Loading statistics..."));
        assertTrue(DuelSideCardView.of(m, DuelSide.CHALLENGER, null).getLines()
                .contains("&8Statistics aren't available right now."));
        assertEquals("Pandi", DuelSideCardView.loading(m, DuelSide.CHALLENGER).getSkullOwner());
    }

    @Test
    void theWinnersCardIsHighlighted() {
        var m = match().ended(DuelEndReason.WIN, DuelSide.CHALLENGER, 5).build();
        assertEquals("HIGHLIGHT", DuelSideCardView.loading(m, DuelSide.CHALLENGER).getDisplayMode());
        assertEquals("&aPandi &7(winner)", DuelSideCardView.loading(m, DuelSide.CHALLENGER).getTitle());
        assertEquals("NORMAL", DuelSideCardView.loading(m, DuelSide.OPPONENT).getDisplayMode());
    }

    @Test
    void stakeTilesAreKitThenCoinsThenEachItem_OrNoStake() {
        var m = match().gear(GearMode.FIXED_KIT, "Knight", "Knight").build();
        List<DuelStakeRowView> rows = DuelStakeRowView.rows(m, DuelSide.OPPONENT);
        assertEquals(List.of(DuelStakeRowView.Kind.KIT, DuelStakeRowView.Kind.COINS, DuelStakeRowView.Kind.ITEM),
                rows.stream().map(DuelStakeRowView::kind).toList());
        assertEquals("&bKit: &fKnight", rows.get(0).getName());
        assertEquals("&6350 coins", rows.get(1).getName());
        assertEquals(("GOLDEN_APPLE"), rows.get(2).getMaterial());
        assertEquals("3", rows.get(2).getAmount());
        assertEquals("&f3x Golden Apple", rows.get(2).getName());
        assertEquals("b", rows.get(2).itemRefId());
        assertNull(rows.get(1).itemRefId());

        List<DuelStakeRowView> none = DuelStakeRowView.rows(match().friendly().build(), DuelSide.CHALLENGER);
        assertEquals(DuelStakeRowView.Kind.NONE, none.getFirst().kind());
        assertEquals(1, none.size());
    }

    // ===== arena_main =====

    @Test
    void theArenaMenuShowsRulesQueueAndClaims() {
        var arena = arena(true, List.of(area(40, "Stands", true)));
        var view = ArenaMenuView.of(arena, new DuelHudView.QueueInfo(2, 3, 5, 8), 4, 2, true);

        assertEquals("&6The Arena", view.getName());
        assertEquals("7", view.getArenaId());
        assertEquals(List.of("&7Waiting: &f2/3", "&7Battlegrounds busy: &f5/8"), view.getQueueLines());
        assertEquals("2", view.getQueueAmount());
        List<String> rules = view.getRulesLines();
        assertTrue(rules.contains("&7Gear: &fown gear, fixed kit"), rules.toString());
        assertTrue(rules.contains("&7Fee: &f5% &7of the coins, paid by the winner"));
        assertTrue(rules.contains("&7Coin stakes: &fup to 10,000 a side"));
        assertTrue(rules.contains("&7Same opponent: &f3 &7staked duels a day, &f10 min &7apart"));
        assertTrue(rules.contains("&7Daily winnings cap: &f25,000 coins"));
        assertTrue(rules.contains("&7Time limit: &f5:00 &7(then a draw)"));
        assertEquals("HIGHLIGHT", view.getClaimsDisplayMode());
        assertTrue(view.getClaimsLines().getFirst().contains("2 deliveries"));
        assertTrue(view.getInfoLines().contains("&7Live duels: &f4"));
    }

    @Test
    void spectatingNeedsAWarpedSpectatorArea_AndChallengingNeedsTheViewerInsideAnOpenArena() {
        var warped = ArenaMenuView.of(arena(true, List.of(area(40, "Stands", false), area(41, "Box", true))), null, 0, 0, true);
        assertEquals("NORMAL", warped.getSpectateDisplayMode());
        assertTrue(warped.getSpectateLines().contains("&f" + "Box" + "&7."), "the first area with a warp");
        assertEquals(41, ArenaMenuView.spectatorWarp(arena(true, List.of(area(40, "Stands", false), area(41, "Box", true)))).id());

        var onFoot = ArenaMenuView.of(arena(true, List.of(area(40, "Stands", false))), null, 0, 0, false);
        assertEquals("DISABLED", onFoot.getSpectateDisplayMode());
        assertEquals("&7Walk to &fStands &7to watch.", onFoot.getSpectateLines().getFirst());
        assertEquals("DISABLED", onFoot.getChallengeDisplayMode(), "outside the arena");
        assertEquals("&7Stand inside the arena", onFoot.getChallengeLines().getFirst());

        assertEquals("NORMAL", warped.getChallengeDisplayMode());
        assertEquals("DISABLED", ArenaMenuView.of(arena(false, List.of()), null, 0, 0, true).getChallengeDisplayMode(),
                "duels closed");
        assertEquals("&7This arena has no spectator area.",
                ArenaMenuView.of(arena(true, List.of()), null, 0, 0, true).getSpectateLines().getFirst());
        assertEquals("false", ArenaMenuView.of(null, null, 0, 0, false).getKnown());
    }

    @Test
    void challengeRowsAreClickableUnlessThePlayerIsBusy() {
        var free = DuelChallengeRowView.of("Steve", null);
        assertEquals(("NORMAL"), free.getDisplayMode());
        assertEquals("Steve", free.getName());
        var busy = DuelChallengeRowView.of("Alex", "in a duel");
        assertEquals("DISABLED", busy.getDisplayMode());
        assertEquals(List.of("&7In a duel."), busy.getLines());
        assertEquals("DISABLED", DuelChallengeRowView.none().getDisplayMode());
        assertEquals("", DuelChallengeRowView.none().getName());
    }

    // ===== sidebar and boss bar =====

    @Test
    void theSidebarPutsTheViewersOwnDuelFirst_ThenTheArenasLiveDuelsAndQueue() {
        var own = match().token("own").phase(DuelPhase.TRAVELLING, 0, 0).build();
        var other = match().token("other").phase(DuelPhase.FIGHT, 60, 300)
                .sides(new DuelMatchSnapshot.Side(UUID.randomUUID(), "Steve", 3, null, 0, List.of()),
                        new DuelMatchSnapshot.Side(UUID.randomUUID(), "Alex", 4, null, 0, List.of()))
                .build();
        var queue = new DuelHudView.QueueInfo(1, 3, 1, 8);

        List<String> duelist = DuelHudView.sidebar(PANDI, List.of(other, own), queue);
        assertEquals("&7Your duel: &fvs Notch", duelist.get(0));
        assertEquals("&7Travelling", duelist.get(1));
        assertTrue(duelist.contains("&7Live duels: &f1"), "only listed matches count: " + duelist);
        assertTrue(duelist.contains("&eSteve vs Alex"));
        assertTrue(duelist.contains(" &7Fighting &f1:00 left"));
        assertEquals("&7Battlegrounds: &f1/8 busy", duelist.getLast());

        List<String> spectator = DuelHudView.sidebar(UUID.randomUUID(), List.of(other, own), queue);
        assertEquals("&7Live duels: &f1", spectator.getFirst());
        assertFalse(spectator.contains("&ePandi vs Notch"), "a match before its warmup isn't listed");
        assertTrue(spectator.size() <= DuelHudView.MAX_LINES);
    }

    @Test
    void theSidebarNeverExceedsFifteenLines() {
        List<DuelMatchSnapshot> many = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> match().token("m" + i).build()).toList();
        List<String> lines = DuelHudView.sidebar(null, many, DuelHudView.QueueInfo.NONE);
        assertTrue(lines.size() <= DuelHudView.MAX_LINES);
        assertTrue(lines.contains("&8+6 more (/duels)"), lines.toString());
    }

    @Test
    void theBossBarFollowsThePhase() {
        assertEquals(new DuelHudView.BossBar("&bWarmup 0:23 &7- &fPandi vs Notch", 23f / 30, "BLUE"),
                DuelHudView.bossBar(match().build()));
        assertEquals(new DuelHudView.BossBar("&eWaiting for a free battleground (#2 in line)", 1f, "YELLOW"),
                DuelHudView.bossBar(match().waiting(2).build()));
        assertEquals("&cGet ready: 7 &7- &fPandi vs Notch",
                DuelHudView.bossBar(match().phase(DuelPhase.BATTLE_COUNTDOWN, 7, 10).build()).text());
        assertEquals(new DuelHudView.BossBar("&cPandi vs Notch &7- &fno time limit", 1f, "RED"),
                DuelHudView.bossBar(match().phase(DuelPhase.FIGHT, 0, 0).build()));
        assertEquals("&aNotch won &7(pending review)",
                DuelHudView.bossBar(match().ended(DuelEndReason.WIN, DuelSide.OPPONENT, 3).held().build()).text());
        assertEquals("&fPandi vs Notch: draw",
                DuelHudView.bossBar(match().ended(DuelEndReason.AGREED_DRAW, null, 3).build()).text());
    }

    @Test
    void aSpectatorFollowsTheNewestFight_ElseTheNewestListedMatch() {
        var oldFight = match().token("a").phase(DuelPhase.FIGHT, 10, 300).build();
        var newWarmup = match().token("b").build();
        assertEquals(oldFight.token(), DuelHudView.followed(List.of(oldFight, newWarmup)).orElseThrow().token());
        assertEquals(newWarmup.token(), DuelHudView.followed(List.of(newWarmup)).orElseThrow().token());
        assertTrue(DuelHudView.followed(List.of(match().phase(DuelPhase.TRAVELLING, 0, 0).build())).isEmpty());
    }

    @Test
    void textHelpers() {
        assertEquals("0:07", DuelViewText.clock(7));
        assertEquals("12:00", DuelViewText.clock(720));
        assertEquals("1 coin + 1 item", DuelViewText.stakes(1, 1));
        assertEquals("no stakes", DuelViewText.stakes(0, 0));
        assertTrue(match().build().involves(NOTCH));
        assertEquals(665, match().build().winnings(), "700 less the 5% fee, rounded down");
    }
}
