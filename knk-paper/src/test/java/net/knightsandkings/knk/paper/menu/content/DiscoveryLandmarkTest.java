package net.knightsandkings.knk.paper.menu.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;
import net.knightsandkings.knk.paper.discovery.DiscoveryMessages;

/**
 * A08 and A09 wording (landmark-domain DESIGN § 7.1 item 5, LD15, LD21, PandiO/knk-workspace#985): a landmark is
 * named a landmark in the discovery message, the discoveries menu and the warp menu, and the discoveries summary counts
 * landmarks although the {@code Landmark} type rule stays off (each landmark is switched on by its override).
 */
class DiscoveryLandmarkTest {

    private static DiscoverySummary summary(DiscoveryTypeCount... counts) {
        return new DiscoverySummary(List.of(counts), null, 0, 0, 0, 0);
    }

    @Test
    void aLandmarkIsCalledALandmark() {
        assertEquals("the landmark", DiscoveryMessages.typePhrase("Landmark"));
        assertEquals("Landmark", DiscoveryRow.typeName("Landmark"));
        assertEquals("LODESTONE", DiscoveryRow.materialFor("landmark"));
        assertEquals("the gate", DiscoveryMessages.typePhrase("GateStructure"), "the others unchanged");
        assertEquals("BRICKS", DiscoveryRow.materialFor("Structure"));
    }

    @Test
    void theSummaryCountsLandmarks_ForPlayersAndStaff_ThoughTheTypeRuleIsOff() {
        DiscoverySummary summary = summary(new DiscoveryTypeCount("Town", 1, 3, true),
            new DiscoveryTypeCount("GateStructure", 0, 2, false), new DiscoveryTypeCount("Landmark", 1, 4, false));

        assertEquals(List.of("&7Towns: &f1&7/&f3", "&7Gates: &f0&7/&f2", "&7Landmarks: &f1&7/&f4"),
            new DiscoveriesView(summary, false).getSummaryLines());
        List<String> staff = new DiscoveriesView(summary, true).getSummaryLines();
        assertEquals("&7Landmarks: &f1&7/&f4", staff.get(2), "not tagged Disabled: landmarks are switched on one by one");
        assertTrue(staff.get(1).contains("Disabled"), "a type that is really off still says so");
        assertEquals("&7Landmarks: &8Disabled", new DiscoveriesView(summary(new DiscoveryTypeCount("Landmark", 0, 0, false)),
            true).getSummaryLines().get(0), "no landmark to discover: the rule's state");
    }
}
