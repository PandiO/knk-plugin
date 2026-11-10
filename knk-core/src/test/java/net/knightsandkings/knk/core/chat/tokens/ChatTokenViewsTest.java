package net.knightsandkings.knk.core.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics.ContextValue;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics.Metric;

/** PandiO/knk-workspace#233: labels and hovers from the public statistics view; hidden metrics are Private. */
class ChatTokenViewsTest {

    private static final PlayerStatistics.Profile PROFILE =
        new PlayerStatistics.Profile("Knight", 1234, 5600, 7, Instant.parse("2026-09-01T10:00:00Z"), 12_300, 600);

    private static Metric metric(String key, Double value, ContextValue... contexts) {
        return new Metric(key, key, value, "count", "sum", List.of(contexts));
    }

    private static PlayerStatistics stats(List<Metric> metrics, PlayerStatistics.Discoveries discoveries) {
        return new PlayerStatistics(7, "Steve", "lifetime", null, null, "UTC", "signedIn", PROFILE, metrics, null, discoveries);
    }

    private static ChatTokenViews.Shown shown(ChatToken token, PlayerStatistics stats, boolean ofOther) {
        return assertInstanceOf(ChatTokenViews.Shown.class, ChatTokenViews.view(token, stats, ofOther));
    }

    @Test
    void killsShowTheCount_WithContextsAndCreatureKillsInTheHover() {
        var s = stats(List.of(metric("pvp_kills", 12d, new ContextValue("open_world", 5), new ContextValue("siege", 7)),
            metric("pve_kills", 40d)), null);

        var view = shown(ChatToken.KILLS, s, false);

        assertEquals("&e[&f12 kills&e]", view.label());
        assertEquals("&6Steve's kills", view.hover().get(0));
        assertEquals("&7Player kills: &f12 &8(Open world 5, Siege 7)", view.hover().get(1));
        assertEquals("&7Creature kills: &f40", view.hover().get(2));
    }

    @Test
    void anotherPlayersLabelNamesThem_AndOneIsSingular() {
        assertEquals("&e[&fSteve: 1 death&e]", shown(ChatToken.DEATHS, stats(List.of(metric("deaths", 1d)), null), true).label());
    }

    @Test
    void aHiddenMetricOrAHiddenTotalIsPrivate_WithItsSettingKey() {
        var none = stats(List.of(), null);
        assertEquals(List.of("pvp_kills"), ((ChatTokenViews.Private) ChatTokenViews.view(ChatToken.KILLS, none, false)).settingKeys());
        var hiddenTotal = stats(List.of(metric("wins", null, new ContextValue("siege", 3))), null);
        assertEquals(List.of("wins"), ((ChatTokenViews.Private) ChatTokenViews.view(ChatToken.WINS, hiddenTotal, false)).settingKeys());
        assertEquals(List.of(ChatTokenViews.DISCOVERIES_SETTING),
            ((ChatTokenViews.Private) ChatTokenViews.view(ChatToken.DISCOVERIES, none, false)).settingKeys());
    }

    @Test
    void kdNeedsBothValues_AndNoDeathsIsTheKillCount() {
        assertEquals(List.of("pvp_kills", "deaths"),
            ((ChatTokenViews.Private) ChatTokenViews.view(ChatToken.KD, stats(List.of(), null), false)).settingKeys());
        assertEquals("&e[&fK/D 2.50&e]", shown(ChatToken.KD, stats(List.of(metric("pvp_kills", 5d), metric("deaths", 2d)), null), false).label());
        assertEquals("3.00", ChatTokenViews.ratio(3, 0));
    }

    @Test
    void profileTokensAreAlwaysShown() {
        var s = stats(List.of(), null);
        assertEquals("&e[&f3h 25m played&e]", shown(ChatToken.PLAYTIME, s, false).label());
        assertEquals("&e[&f5,600 coins&e]", shown(ChatToken.BALANCE, s, false).label());
        assertEquals("&7Gems: &b7", shown(ChatToken.BALANCE, s, false).hover().get(2));
        assertEquals("&e[&fKnight&e]", shown(ChatToken.TITLE, s, false).label());
        assertEquals("&e[&f1,234 XP&e]", shown(ChatToken.XP, s, false).label());
    }

    @Test
    void statsIsTheShortOverview_WithOnlyWhatThePublicViewHas() {
        var s = stats(List.of(metric("pvp_kills", 4d), metric("deaths", 2d)), new PlayerStatistics.Discoveries(9, 2, 3, 4));

        var view = shown(ChatToken.STATS, s, false);

        assertEquals("&e[&fSteve's stats&e]", view.label());
        assertTrue(view.hover().contains("&7K/D: &f2.00"));
        assertTrue(view.hover().contains("&7Discoveries: &f9"));
        assertTrue(view.hover().stream().noneMatch(l -> l.contains("Wins")));
        assertEquals("&8/stats Steve for more", view.hover().get(view.hover().size() - 1));
        assertEquals("Chris'", ChatTokenViews.possessive("Chris"));
    }

    @Test
    void anotherPlayersNameTakesTheGivenColour_OwnTokensDoNot() {
        var s = stats(List.of(metric("pvp_kills", 3d)), null);

        assertEquals("&e[&9Steve&f: 3 kills&e]", shown(ChatToken.KILLS, s, true, "&9").label());
        assertEquals("&e[&9Steve's&f stats&e]", shown(ChatToken.STATS, s, true, "&9").label());
        assertEquals("&e[&f3 kills&e]", shown(ChatToken.KILLS, s, false, "&9").label());
        assertEquals("&e[&fSteve: 3 kills&e]", shown(ChatToken.KILLS, s, true, null).label());
    }

    private static ChatTokenViews.Shown shown(ChatToken token, PlayerStatistics stats, boolean ofOther, String nameColor) {
        return assertInstanceOf(ChatTokenViews.Shown.class, ChatTokenViews.view(token, stats, ofOther, nameColor));
    }
}
