package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions.SpawnOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 8b: the menu views' text and item facts (MENU_TEMPLATES.md C.2-C.4). */
class SiegeMenuViewsTest {

    private static KnkSiegeScenario scenario(boolean recapture) {
        var objectives = List.of(
                new KnkSiegeObjective(1, 0, "Keep", null, null, 500, 2.5, true, 1, true, SiegeGateState.OPEN),
                new KnkSiegeObjective(2, 1, "Gatehouse", null, 10, 500, 2.5, false, 1, true, SiegeGateState.OPEN));
        return new KnkSiegeScenario(100, "Siege of Cinix", null, 1, "Cinix", null,
                List.of(new KnkSiegeDistrict(10, "Old Town", "cinix_old")), null, 2, 20, null, null,
                KnkSiegeMatchLength.DEFAULT, null, true, recapture, true, List.of(), objectives,
                List.of(new KnkSiegeGate(10, "Main gate", 1, SiegeGateState.CLOSED, true, true)), List.of());
    }

    private static SiegeMenuSnapshot snapshot(SiegePhase phase, KnkSiegeScenario drawn, List<SiegeMenuSnapshot.Team> teams) {
        return new SiegeMenuSnapshot(3, "Siege - Cinix", phase, 125, phase == SiegePhase.MATCHMAKING,
                phase == SiegePhase.MATCHMAKING, 20, drawn, drawn == null ? List.of(scenario(false)) : List.of(), true,
                Map.of(100, 2), 1, "Squire",
                List.of(new SiegeMenuSnapshot.Member(UUID.randomUUID(), "Alice", "Knight", "Premium", null)),
                List.of(), teams);
    }

    @Test
    void lobbyView_phaseFacts_andLines() {
        var matchmaking = new SiegeLobbyMenuView(snapshot(SiegePhase.MATCHMAKING, null, List.of()), false, null);
        assertEquals("GREEN_BANNER", matchmaking.getBannerMaterial());
        assertEquals("Matchmaking", matchmaking.getPhaseLabel());
        assertEquals("Starts in:", matchmaking.getTimerLabel());
        assertEquals("2:05", matchmaking.getTimeRemaining());
        assertEquals("to be voted (Siege of Cinix)", matchmaking.getScenarioLabel());
        assertEquals("&a1&7/&a20", matchmaking.getMemberCountLabel());
        assertEquals(1, matchmaking.getMemberCountOrOne());
        assertEquals("&7Entry: &eSquire", matchmaking.getEntryRequirementLine());
        assertEquals(List.of("&aClick to view and join!"), matchmaking.getJoinHintLines());
        assertNull(matchmaking.getPlayersMinLine());
        assertFalse(matchmaking.getScenarioKnown());

        var denied = new SiegeLobbyMenuView(snapshot(SiegePhase.MATCHMAKING, null, List.of()), false, "it is full");
        assertEquals(List.of("&cCan't join: it is full", "&7Click to view"), denied.getJoinHintLines());

        var teams = List.of(new SiegeMenuSnapshot.Team(1, "Garrison", "GOLD", 3, 2, null),
                new SiegeMenuSnapshot.Team(2, "Raiders", "RED", 2, 0, null));
        var running = new SiegeLobbyMenuView(snapshot(SiegePhase.IN_PROGRESS, scenario(true), teams), true, null);
        assertEquals("RED_BANNER", running.getBannerMaterial());
        assertEquals("Ends in:", running.getTimerLabel());
        assertEquals("Siege of Cinix", running.getScenarioLabel());
        assertEquals(2, running.getObjectiveCount());
        assertEquals(1, running.getGateObjectiveCount());
        assertEquals("&7Min. players: &a2", running.getPlayersMinLine());
        assertEquals(List.of("&6Garrison&7: &a3 &7players, &a2 &7objectives held", "&cRaiders&7: &a2 &7players, &a0 &7objectives held"),
                running.getTeamSummaryLines());
        assertEquals("&7Captured objectives can be retaken.", running.getRecaptureLine());
        assertEquals(List.of("&aYou are in this siege - click to open it"), running.getJoinHintLines());
        assertEquals("siege-lobby:3", running.menuRowKey());
    }

    @Test
    void viewerView_joinLeaveAndTeam() {
        var outsider = SiegeViewerMenuView.outsider("You need at least the title Squire");
        assertEquals("GREEN_CONCRETE", outsider.getJoinLeaveMaterial());
        assertEquals("&aClick to join", outsider.getJoinLeaveName());
        assertEquals("&cYou need at least the title Squire", outsider.getJoinDenialLine());
        assertNull(outsider.getTeamLine());
        assertEquals("Default", outsider.getCurrentSpawnName());

        var member = new SiegeViewerMenuView(true, null, new SiegeMenuSnapshot.Team(2, "Raiders", "RED", 2, 0, "BLACK|skull:WHITE"), "Camp");
        assertTrue(member.getIsMember());
        assertEquals("SPRUCE_DOOR", member.getJoinLeaveMaterial());
        assertEquals("&7Your team: &cRaiders", member.getTeamLine());
        assertEquals("BLACK|skull:WHITE", member.getTeamBannerPatterns());
        assertNull(member.getJoinDenialLine());
        assertEquals("Camp", member.getCurrentSpawnName());
    }

    @Test
    void voteOptions_scenarioAndRandom() {
        var option = new SiegeVoteOptionView(scenario(false), 2, true, true, "Squire");
        assertEquals("100", option.getChoiceKey());
        assertEquals("HIGHLIGHT", option.getDisplayMode());
        assertEquals("&aSiege of Cinix", option.getTitle());
        assertTrue(option.getLines().contains("&7Districts: &aOld Town"));
        assertTrue(option.getLines().contains("&7Entry: &eSquire"));
        assertEquals("&cVoted - click to remove", option.getLines().get(option.getLines().size() - 1));

        var random = new SiegeVoteOptionView(null, 0, false, false, null);
        assertEquals(SiegeMenuIds.VOTE_RANDOM, random.getChoiceKey());
        assertEquals("&5Random", random.getTitle());
        assertEquals(1, random.getVoteCountOrOne());
        assertEquals("&cJoin the Siege to vote", random.getLines().get(random.getLines().size() - 1));
    }

    @Test
    void bodyRows_memberAndObjective() {
        var alice = new SiegeMenuSnapshot.Member(UUID.randomUUID(), "Alice", "Knight", "Premium", 2);
        var raiders = new SiegeMenuSnapshot.Team(2, "Raiders", "RED", 2, 1, null);
        var memberRow = SiegeBodyRowView.member(alice, true, raiders);
        assertEquals("PLAYER_HEAD", memberRow.getMaterial());
        assertEquals("Alice", memberRow.getSkullOwner());
        assertEquals("HIGHLIGHT", memberRow.getDisplayMode());
        assertEquals(List.of("&bTitle: &aKnight", "&bRank: &aPremium", "&7Team: &cRaiders"), memberRow.getLines());

        var keep = new SiegeMenuSnapshot.Objective(1, "Keep", true, 2, 140, true, 2, "Alice", "Main gate", "Open", "WHITE|stripe_top:RED");
        var objectiveRow = SiegeBodyRowView.objective(keep, raiders, true);
        assertEquals("WHITE_BANNER", objectiveRow.getMaterial());
        assertNull(objectiveRow.getSkullOwner());
        assertEquals("WHITE|stripe_top:RED", objectiveRow.getBannerPatterns());
        assertEquals("&7Keep &7(&aWin&7)", objectiveRow.getTitle());
        assertEquals(List.of("&7Held by: &cRaiders", "", "&7Captured: &a100%", "&7Captured by: &aAlice", "&7Captures: &a2",
                "&7Gate: &aMain gate &7(Open)", "&cBeing captured!"), objectiveRow.getLines());
    }

    @Test
    void spawnOptions_states() {
        var held = new SpawnOptionView(new SpawnOption(SpawnKind.OBJECTIVE, 2, "Gatehouse", null, false, false), "WHITE|");
        assertEquals("objective:2", held.getOption());
        assertEquals("DISABLED", held.getDisplayMode());
        assertEquals(List.of("&cCan't spawn here", "&cObjective is being captured!"), held.getStatusLines());
        assertEquals("WHITE|", held.getBannerPatterns());

        var camp = new SpawnOptionView(new SpawnOption(SpawnKind.SPAWNPOINT, 7, "Camp", null, true, true), "ignored");
        assertEquals("spawnpoint:7", camp.getOption());
        assertEquals("GREEN_CONCRETE", camp.getMaterial());
        assertNull(camp.getBannerPatterns());
        assertEquals("HIGHLIGHT", camp.getDisplayMode());
        assertEquals("&7Spawnpoint Camp", camp.getName());
        assertEquals(List.of("&aCurrent spawnpoint"), camp.getStatusLines());
    }

    @Test
    void format_colorsDurationsAndBanners() {
        assertEquals("&6", SiegeMenuFormat.color("gold"));
        assertEquals("&f", SiegeMenuFormat.color("nonsense"));
        assertEquals("0:00", SiegeMenuFormat.duration(-5));
        assertEquals("1:01:05", SiegeMenuFormat.duration(3665));
        var design = new KnkBannerDesign(1, "Crown", "yellow",
                List.of(new KnkBannerLayer(2, 1, "border", "black"), new KnkBannerLayer(1, 0, "stripe_top", "red")));
        assertEquals("YELLOW|stripe_top:RED,border:BLACK", SiegeMenuFormat.bannerPatterns(design));
        assertNull(SiegeMenuFormat.bannerPatterns((KnkBannerDesign) null));
    }
}
