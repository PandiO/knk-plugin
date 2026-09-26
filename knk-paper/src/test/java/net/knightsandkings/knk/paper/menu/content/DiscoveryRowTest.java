package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Domain discovery DESIGN.md §3.7: how one place shows in {@code discoveries.main}. */
class DiscoveryRowTest {

    private static final OffsetDateTime WHEN = OffsetDateTime.of(2026, 9, 21, 1, 30, 0, 0, ZoneOffset.ofHours(2));

    private static DiscoveryProgressRow discovered(int id, String name, String type, String town) {
        return new DiscoveryProgressRow(id, name, type, town, true, WHEN, 120, 0, 40);
    }

    private static DiscoveryProgressRow undiscovered(int id, String name, String type, String town) {
        return new DiscoveryProgressRow(id, name, type, town, false, null, 0, 0, 0);
    }

    @Test
    void discoveredPlaceShowsTypeTownDateAndReward() {
        DiscoveryRow row = DiscoveryRow.of(discovered(14, "Market", "District", "Rivia"), 3);

        assertEquals(14, row.getDomainId());
        assertEquals("&aMarket", row.getName());
        assertEquals("OAK_SIGN", row.getMaterial());
        assertEquals("NORMAL", row.getDisplayMode());
        assertEquals(List.of("&7District in &fRivia", "&7Discovered: &f2026-09-20", "&7Reward: &6+120 coins &d+40 XP"),
                row.getLoreLines(), "UTC date; zero gems left out");
    }

    @Test
    void theLatestDiscoveryIsHighlighted() {
        DiscoveryRow row = DiscoveryRow.of(discovered(3, "Rivia", "Town", null), 3);

        assertEquals("HIGHLIGHT", row.getDisplayMode());
        assertEquals("&7Town", row.getLoreLines().get(0));
        assertTrue(row.getLoreLines().contains("&eYour latest discovery"));
    }

    @Test
    void materialsFollowTheType() {
        assertEquals("FILLED_MAP", DiscoveryRow.of(discovered(1, "A", "Town", null), null).getMaterial());
        assertEquals("OAK_SIGN", DiscoveryRow.of(discovered(2, "B", "District", "A"), null).getMaterial());
        assertEquals("BRICKS", DiscoveryRow.of(discovered(3, "C", "Structure", "A"), null).getMaterial());
        assertEquals("IRON_BARS", DiscoveryRow.of(discovered(4, "D", "GateStructure", "A"), null).getMaterial());
        assertEquals("&7Gate in &fA", DiscoveryRow.of(discovered(4, "D", "GateStructure", "A"), null).getLoreLines().get(0));
    }

    @Test
    void undiscoveredTownsShowTheirName() {
        DiscoveryRow row = DiscoveryRow.of(undiscovered(5, "Nordvik", "Town", null), null);

        assertEquals("&7Nordvik", row.getName());
        assertEquals("GRAY_DYE", row.getMaterial());
        assertEquals("DISABLED", row.getDisplayMode());
        assertEquals(List.of("&7Town", "&8Not yet discovered"), row.getLoreLines());
    }

    @Test
    void undiscoveredDistrictsAndStructuresAreMaskedWithTheirTown() {
        DiscoveryRow district = DiscoveryRow.of(undiscovered(6, "Secret Garden", "District", "Rivia"), null);
        DiscoveryRow orphan = DiscoveryRow.of(undiscovered(7, "Hut", "Structure", null), null);

        assertEquals("&8???", district.getName());
        assertEquals(List.of("&7District", "&7Somewhere in &fRivia"), district.getLoreLines());
        assertFalse(String.join(" ", district.getLoreLines()).contains("Secret Garden"));
        assertEquals("&8???", orphan.getName());
        assertEquals(List.of("&7Structure", "&8Not yet discovered"), orphan.getLoreLines());
    }

    @Test
    void rewardsLeaveOutZeroAmounts() {
        assertEquals("&6+1 coins &b+2 gems &d+3 XP", DiscoveryRow.rewards(1, 2, 3));
        assertNull(DiscoveryRow.rewards(0, 0, 0));
        assertEquals(List.of("&7Structure in &fRivia", "&7Discovered: &f2026-09-20"),
                DiscoveryRow.of(new DiscoveryProgressRow(8, "Hut", "Structure", "Rivia", true, WHEN, 0, 0, 0), null).getLoreLines());
    }

    @Test
    void rowKeyChangesWithWhatThePlayerSees() {
        DiscoveryRow before = DiscoveryRow.of(undiscovered(6, "Market", "District", "Rivia"), null);
        DiscoveryRow after = DiscoveryRow.of(discovered(6, "Market", "District", "Rivia"), null);

        assertNotEquals(before.menuRowKey(), after.menuRowKey());
        assertEquals(after, DiscoveryRow.of(discovered(6, "Market", "District", "Rivia"), null));
    }
}
