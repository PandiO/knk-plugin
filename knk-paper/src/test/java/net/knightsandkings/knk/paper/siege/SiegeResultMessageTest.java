package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.paper.siege.SiegeResultMessage.MemberResult;
import net.knightsandkings.knk.paper.siege.SiegeResultMessage.Outcome;
import net.knightsandkings.knk.paper.siege.SiegeResultMessage.RewardState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alpha run 1 (PandiO/knk-workspace#164): the compact result line with every statistic and reward in its hover,
 * zeros included, and the match link (#174); delivered once with or without the rewards (#198).
 */
class SiegeResultMessageTest {

    private static final KnkSiegeTeam RAIDERS = new KnkSiegeTeam(202, 1, SiegeTeamRole.ATTACKER, 2, null, "Raiders", "RED",
            null, null, List.of());
    private static final KnkSiegeTeam GARRISON = new KnkSiegeTeam(201, 0, SiegeTeamRole.DEFENDER, 1, null, "Garrison", "GOLD",
            null, null, List.of());

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** The hover text of the first component (with children) that carries one. */
    private static String hover(Component c) {
        HoverEvent<?> event = c.hoverEvent();
        if (event != null && event.value() instanceof Component shown) return plain(shown);
        for (Component child : c.children()) {
            String found = hover(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String openUrl(Component c) {
        ClickEvent<?> click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.OPEN_URL) return ((ClickEvent.Payload.Text) click.payload()).value();
        for (Component child : c.children()) {
            String found = openUrl(child);
            if (found != null) return found;
        }
        return null;
    }

    private static MemberResult quiet(Outcome outcome) {
        return new MemberResult(outcome, RAIDERS, 0, 0, 0, 0, 0, 0, 600);
    }

    @Test
    void theCompactLineHasTheOutcomeKillsAndDeaths_andTheHoverEveryStatIncludingZeros() {
        Component line = SiegeResultMessage.summary("Siege - Cinix", "time ran out", quiet(Outcome.DRAW), RewardState.PENDING,
                null, null);

        assertEquals("[Siege] Draw 0 kills · 0 deaths [details]", plain(line));
        String details = hover(line);
        assertNotNull(details);
        for (String expected : List.of("Siege - Cinix - Draw", "Ended: time ran out", "Team: Raiders", "Kills: 0", "Deaths: 0",
                "Best kill streak: 0", "Objectives captured: 0", "Gate damage: 0 HP", "Active time: 00:00 of 10:00",
                "Being confirmed by the server")) {
            assertTrue(details.contains(expected), expected + " in:\n" + details);
        }
        assertNull(openUrl(line), "no link without web.public-url");
    }

    @Test
    void withTheServersRewards_theLineSaysWhatWasEarned_andTheHoverBreaksItDown() {
        MemberResult carol = new MemberResult(Outcome.WIN, RAIDERS, 4, 1, 3, 2, 312, 590, 600);
        ParticipantReward reward = new ParticipantReward(3, true, true, 1, 2, 330, 31, 1, 275,
                List.of(new RewardMultiplier("personal", 1.2, null, null, false, null, null)), 312, 3, true, 590, 600);

        Component line = SiegeResultMessage.summary("Siege - Cinix", "main objective captured", carol, RewardState.GRANTED, reward,
                "https://knk.example/siege/matches/41");

        String text = plain(line);
        assertTrue(text.startsWith("[Siege] Victory! 4 kills · 1 death · +330 coins, +1 gems, +31 XP [details]"), text);
        assertTrue(text.endsWith("[match page]"), text);
        assertEquals("https://knk.example/siege/matches/41", openUrl(line));
        String details = hover(line);
        for (String expected : List.of("Kills: 4", "Deaths: 1", "Best kill streak: 3", "Objectives captured: 2", "Gate damage: 312 HP",
                "Gems: +1", "XP: +31", "Win: yes", "Objectives gained by your team: 1", "Objectives you captured: 2",
                "Gate damage: 312 HP (3 full steps)", "Taking part: yes", "= +330 coins")) {
            assertTrue(details.contains(expected), expected + " in:\n" + details);
        }
    }

    @Test
    void aMemberWithoutRewards_seesZerosAndWhyTakingPartDidNotPay() {
        ParticipantReward none = new ParticipantReward(5, true, false, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, false, 100, 600);

        String details = hover(SiegeResultMessage.summary("Siege", "time ran out", quiet(Outcome.LOSS), RewardState.GRANTED, none, null));

        for (String expected : List.of("Coins: +0", "Gems: +0", "XP: +0", "Win: no", "Gate damage: 0 HP (0 full steps)",
                "Taking part: no (active 01:40 of 10:00)")) {
            assertTrue(details.contains(expected), expected + " in:\n" + details);
        }
    }

    @Test
    void spooledAndUnrecordedResultsSayWhyThereAreNoRewardsYet() {
        assertTrue(hover(SiegeResultMessage.summary("S", "r", quiet(Outcome.WIN), RewardState.SPOOLED, null, null))
                .contains("couldn't reach the API"));
        assertTrue(hover(SiegeResultMessage.summary("S", "r", quiet(Outcome.WIN), RewardState.UNRECORDED, null, null))
                .contains("no rewards are granted this round"));
    }

    @Test
    void theOutcomeFollowsTheMembersAlliance() {
        AllianceResolver alliances = new AllianceResolver(List.of(GARRISON, RAIDERS));
        WinResolver.Result raidersWin = new WinResolver.Result(SiegeEndReason.INSTANT_VICTORY, OptionalInt.of(2),
                WinResolver.Decision.INSTANT_VICTORY_CAPTURE);
        WinResolver.Result draw = new WinResolver.Result(SiegeEndReason.TIME_EXPIRED, OptionalInt.empty(), WinResolver.Decision.DRAW);

        assertEquals(Outcome.WIN, SiegeResultMessage.outcomeOf(raidersWin, alliances, 202));
        assertEquals(Outcome.LOSS, SiegeResultMessage.outcomeOf(raidersWin, alliances, 201));
        assertEquals(Outcome.LOSS, SiegeResultMessage.outcomeOf(raidersWin, alliances, 999));
        assertEquals(Outcome.DRAW, SiegeResultMessage.outcomeOf(draw, alliances, 201));
    }

    @Test
    void theMatchPageUrlIsBuiltFromThePublicUrl() {
        assertEquals("https://knk.example/siege/matches/7", SiegeResultMessage.matchPageUrl("https://knk.example/", 7));
        assertNull(SiegeResultMessage.matchPageUrl("  ", 7));
        assertNull(SiegeResultMessage.matchPageUrl(null, 7));
    }

    // ==================== Delivery (#164 / #198) ====================

    private final UUID carol = UUID.randomUUID();
    private final Map<UUID, List<String>> chat = new HashMap<>();

    private SiegeResultDelivery delivery() {
        return new SiegeResultDelivery("Siege", "time ran out", Map.of(carol, quiet(Outcome.WIN)), Map.of(3, carol), null,
                (id, message) -> chat.computeIfAbsent(id, k -> new ArrayList<>()).add(plain(message)));
    }

    private static RewardSummary rewards(int coins) {
        return new RewardSummary(41, List.of(new ParticipantReward(3, true, true, 0, 0, coins, 0, 0)));
    }

    @Test
    void whenTheRewardsComeFirst_theResultIsShownOnceWithThem() {
        SiegeResultDelivery delivery = delivery();

        delivery.deliver(RewardState.GRANTED, rewards(100));
        delivery.deliver(RewardState.PENDING, null); // the timeout fires later: nothing more

        assertEquals(1, chat.get(carol).size());
        assertTrue(chat.get(carol).get(0).contains("+100 coins"), chat.get(carol).get(0));
    }

    @Test
    void whenTheRewardsAreLate_theResultIsShownWithoutThem_andTheyFollowOnce() {
        SiegeResultDelivery delivery = delivery();

        delivery.deliver(RewardState.PENDING, null);
        delivery.deliver(RewardState.GRANTED, rewards(100));
        delivery.deliver(RewardState.GRANTED, rewards(100));

        List<String> lines = chat.get(carol);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("[Siege] Victory! 0 kills"), lines.get(0));
        assertTrue(lines.get(1).startsWith("[Siege] Your rewards: +100 coins"), lines.get(1));
    }

    @Test
    void aSpooledAnswerAfterTheResultSaysTheRewardsCouldNotBeConfirmed() {
        SiegeResultDelivery delivery = delivery();

        delivery.deliver(RewardState.PENDING, null);
        delivery.deliver(RewardState.SPOOLED, null);

        assertTrue(chat.get(carol).get(1).contains("couldn't be confirmed yet"), chat.get(carol).get(1));
    }
}
