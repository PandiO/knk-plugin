package net.knightsandkings.knk.core.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-34 link 5: statistics lines for {@code /stats} and {@code statistics.main} (DESIGN.md §F.1, §F.10). */
class StatisticsLinesTest {

    private static PlayerStatistics.Metric metric(String key, Double value, String unit, PlayerStatistics.ContextValue... contexts) {
        return new PlayerStatistics.Metric(key, key, value, unit, "Sum", List.of(contexts));
    }

    private static PlayerStatistics statistics(String viewer, String period, LocalDate start, PlayerStatistics.Economy economy,
                                               PlayerStatistics.Discoveries discoveries, PlayerStatistics.Metric... metrics) {
        return new PlayerStatistics(7, "Bob", period, start, null, "Europe/Amsterdam", viewer,
                new PlayerStatistics.Profile("Squire", 12_345, 1, 2, Instant.parse("2026-09-01T22:30:00Z"), 90_061, 59),
                List.of(metrics), economy, discoveries);
    }

    @Test
    void valuesAreFormattedPerUnit() {
        assertEquals("1d 1h", StatisticsLines.format("active_playtime", "Seconds", 90_061));
        assertEquals("1h 1m", StatisticsLines.duration(3_661));
        assertEquals("5m", StatisticsLines.duration(300));
        assertEquals("59s", StatisticsLines.duration(59));
        assertEquals("1,234 blocks", StatisticsLines.format("distance.foot", "Blocks", 1234.9));
        assertEquals("23.5 blocks", StatisticsLines.format("highest_fall", "Blocks", 23.5));
        assertEquals("1,501", StatisticsLines.format("gate_damage", "Points", 1501));
        assertEquals("12", StatisticsLines.format("pvp_kills", "Count", 12));
    }

    @Test
    void labelsGroupsContextsAndPeriods() {
        assertEquals("Highest survived fall", StatisticsLines.label("highest_fall"));
        assertEquals(StatisticsLines.MINIGAMES, StatisticsLines.group("gate_damage"));
        assertEquals(StatisticsLines.OTHER, StatisticsLines.group("future_metric"));
        assertEquals("future_metric", StatisticsLines.label("future_metric"));
        assertEquals("Open world", StatisticsLines.contextLabel("open_world"));
        assertEquals("Siege survival", StatisticsLines.contextLabel("siege_survival"));
        assertEquals("Lifetime", StatisticsLines.periodLabel("lifetime", null));
        assertEquals("Week of 28 Sep 2026", StatisticsLines.periodLabel("week", LocalDate.of(2026, 9, 28)));
        assertEquals("October 2026", StatisticsLines.periodLabel("month", LocalDate.of(2026, 10, 1)));
        assertEquals("day", StatisticsLines.nextPeriod("lifetime"));
        assertEquals("lifetime", StatisticsLines.nextPeriod("month"));
    }

    @Test
    void metricLinesShowContexts_AndOnlyVisibleContextsWhenTheTotalIsHidden() {
        assertEquals("&7Player kills: &f12 &8(Open world 5, Siege 7)", StatisticsLines.metricLine(metric("pvp_kills", 12d, "Count",
                new PlayerStatistics.ContextValue("open_world", 5), new PlayerStatistics.ContextValue("siege", 7))));
        assertEquals("&7Player kills: &fOpen world 5", StatisticsLines.metricLine(metric("pvp_kills", null, "Count",
                new PlayerStatistics.ContextValue("open_world", 5))));
        assertEquals("&7Deaths: &f3", StatisticsLines.metricLine(metric("deaths", 3d, "Count")));
    }

    @Test
    void groupsListTheirMetricsInCatalogueOrder_PlusEconomyAndDiscoveries() {
        PlayerStatistics stats = statistics("self", "lifetime", null,
                new PlayerStatistics.Economy(1_500, 20, 3, 0), new PlayerStatistics.Discoveries(5, 1, 2, 2),
                metric("deaths", 3d, "Count"), metric("pvp_kills", 12d, "Count"), metric("highest_fall", 23.5, "Blocks"),
                metric("xp_gained", 300d, "Count"), metric("future_metric", 1d, "Count"));

        assertEquals(List.of("&7Player kills: &f12", "&7Deaths: &f3"), StatisticsLines.groupLines(stats, StatisticsLines.COMBAT));
        assertEquals(List.of("&7Highest survived fall: &f23.5 blocks",
                "&7Discoveries: &f5 &8(towns 1, districts 2, structures 2)"), StatisticsLines.groupLines(stats, StatisticsLines.EXPLORATION));
        assertEquals(List.of("&7XP gained: &f300", "&7Coins earned/spent: &61,500&7/&620", "&7Gems earned/spent: &b3&7/&b0"),
                StatisticsLines.groupLines(stats, StatisticsLines.PROGRESSION));
        assertEquals(List.of("&7future_metric: &f1"), StatisticsLines.groupLines(stats, StatisticsLines.OTHER));
    }

    @Test
    void lines_ShowThePeriodProfileAndNonEmptyGroups() {
        PlayerStatistics stats = statistics("self", "week", LocalDate.of(2026, 9, 28), null, null,
                metric("active_playtime", 600d, "Seconds"), metric("pvp_kills", 2d, "Count"));

        List<String> lines = StatisticsLines.lines(stats);

        assertEquals("&7Period: &fWeek of 28 Sep 2026", lines.get(0));
        assertTrue(lines.contains("&7Title: &fSquire"), lines.toString());
        assertTrue(lines.contains("&7Experience: &f12,345"), lines.toString());
        assertTrue(lines.contains("&7Active playtime: &f1d 1h &8| &7AFK: &f59s"), lines.toString());
        assertTrue(lines.contains("&7First joined: &f1 Sep 2026"), lines.toString());
        assertTrue(lines.contains("&6Activity"), lines.toString()); // a period's playtime is shown
        assertTrue(lines.contains(" &7Active playtime: &f10m"), lines.toString());
        assertTrue(lines.contains(" &7Player kills: &f2"), lines.toString());
        assertFalse(lines.contains("&6Minigames"), lines.toString());
    }

    @Test
    void lines_HintWhenAnotherViewerSeesOnlyTheAlwaysPublicFields() {
        PlayerStatistics stats = statistics("signedIn", "lifetime", null, null, null,
                metric("active_playtime", 90_061d, "Seconds"), metric("xp_gained", 10d, "Count"));

        List<String> lines = StatisticsLines.lines(stats, false);

        assertFalse(lines.contains("&6Activity"), lines.toString()); // lifetime playtime is the profile line
        assertTrue(lines.contains(" &7XP gained: &f10"), lines.toString());
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("&7Title:")), lines.toString());
        assertEquals("&8Bob keeps their other statistics private.", lines.get(lines.size() - 1));
        assertTrue(StatisticsLines.lines(statistics("self", "lifetime", null, null, null)).stream()
                .noneMatch(l -> l.contains("private")));
    }
}
