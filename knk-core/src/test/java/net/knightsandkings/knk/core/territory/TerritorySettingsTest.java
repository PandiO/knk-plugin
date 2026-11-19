package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The entry and exit lines and the settings' defaults (production.territory.*). */
class TerritorySettingsTest {

    private final TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 0), cuboid("d", 0, 0, 15, 15)));

    private TerritoryTracker.Change change(TerritoryVerdict from, TerritoryVerdict to) {
        return new TerritoryTracker.Change(UUID.randomUUID(), from, to, cache);
    }

    @Test
    void theDefaultLines_nameTheTownTheKingdomAndTheContestingHolders() {
        TerritorySettings settings = TerritorySettings.disabled();
        TerritoryVerdict phoenix = TerritoryRules.resolve(List.of(TerritoryHolder.town(1)), null);

        assertFalse(settings.enabled());
        assertEquals("&7Entering the territory of &fPhoenix", settings.messageFor(change(TerritoryVerdict.WILDERNESS, phoenix)));
        assertEquals("&7Entering the crown land of &fAurelia", settings.messageFor(change(phoenix,
                TerritoryRules.resolve(List.of(TerritoryHolder.kingdom(7)), null))));
        assertEquals("&6Entering contested land: &fPhoenix / Rivertown", settings.messageFor(change(phoenix,
                TerritoryRules.resolve(List.of(TerritoryHolder.town(1), TerritoryHolder.town(2)), null))));
        assertEquals("&7Leaving &fPhoenix&7: wilderness", settings.messageFor(change(phoenix, TerritoryVerdict.WILDERNESS)));
    }

    @Test
    void configuredLines_replaceTheDefaults_unknownKeysAreIgnored_andBoundsHold() {
        TerritorySettings settings = new TerritorySettings(true, -5, true, true, false, 0,
                Map.of("enter-town", "Welcome to {name}", "nonsense", "x"));

        assertEquals("Welcome to Phoenix", settings.messageFor(change(TerritoryVerdict.WILDERNESS,
                TerritoryRules.resolve(List.of(TerritoryHolder.town(1)), null))));
        assertEquals(4, settings.messages().size());
        assertEquals(0, settings.refreshMinutes());
        assertEquals(TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD, settings.maxChunksPerWorld());
    }
}
