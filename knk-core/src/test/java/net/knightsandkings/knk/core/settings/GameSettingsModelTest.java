package net.knightsandkings.knk.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldSettings;

/** The Game Settings model and announcement texts (docs/specs/game-settings/DESIGN.md, KNG-52). */
class GameSettingsModelTest {

    private static KnkWorldSettings world(String name, KnkRespawnPolicy policy) {
        return new KnkWorldSettings(name, name, "ADVENTURE", false, 18000, null, null, policy);
    }

    @Test
    void worldsAreFoundCaseInsensitively() {
        KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null, null, null, null,
            List.of(world("World", null)), null);
        assertTrue(settings.world("world").isPresent());
        assertTrue(settings.world("other").isEmpty());
        assertTrue(settings.world(null).isEmpty());
    }

    @Test
    void respawnPolicyIsTheWorldsOwnElseTheDefault() {
        KnkRespawnPolicy nearest = new KnkRespawnPolicy(KnkRespawnPolicy.Mode.NEAREST_TOWN, null, null, true);
        KnkRespawnPolicy configured = new KnkRespawnPolicy(KnkRespawnPolicy.Mode.CONFIGURED_REFERENCE, null, null, false);
        KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null, null, null, configured,
            List.of(world("world", nearest)), null);
        assertEquals(nearest, settings.respawnPolicyFor("WORLD"));
        assertEquals(configured, settings.respawnPolicyFor("world_the_end"));
    }

    @Test
    void missingPartsGetTheApiDefaults() {
        KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null, null, null, null,
            Arrays.asList(null, world(null, null), world("w", null)), null);
        assertEquals(1, settings.worldSettings().size());
        assertEquals(KnkRespawnPolicy.Mode.WORLD_SPAWN, settings.defaultRespawnPolicy().mode());
        KnkWorldSettings w = settings.worldSettings().get(0);
        assertEquals(KnkWeatherSettings.Mode.NORMAL, w.weather().mode());
        assertTrue(w.respawnPolicy().useWorldSpawnFallback());
    }

    @Test
    void lockedTimeWrapsIntoOneDay() {
        assertEquals(1000, new KnkWorldSettings("w", null, null, true, 25000, null, null, null).lockedTime());
        assertEquals(23000, new KnkWorldSettings("w", null, null, true, -1000, null, null, null).lockedTime());
    }

    @Test
    void apiValuesParseLeniently() {
        assertEquals(KnkRespawnPolicy.Mode.CONFIGURED_REFERENCE, KnkRespawnPolicy.Mode.parse("ConfiguredReference"));
        assertEquals(KnkRespawnPolicy.Mode.NEAREST_TOWN, KnkRespawnPolicy.Mode.parse("nearest_town"));
        assertEquals(KnkRespawnPolicy.Mode.WORLD_SPAWN, KnkRespawnPolicy.Mode.parse("Bogus"));
        assertEquals(KnkWeatherSettings.Mode.WEIGHTED, KnkWeatherSettings.Mode.parse("Weighted"));
        assertEquals(KnkWeatherSettings.Mode.NORMAL, KnkWeatherSettings.Mode.parse(null));
        assertEquals(KnkWeather.THUNDER, KnkWeather.parse("thunder"));
        assertNull(KnkWeather.parse("SNOW"));
        assertEquals(KnkWeather.CLEAR, KnkWeather.of(false, true));
        assertEquals(KnkWeather.RAIN, KnkWeather.of(true, false));
        assertEquals(KnkWeather.THUNDER, KnkWeather.of(true, true));
    }

    @Test
    void announcementsFillThePlayerAndBlankMeansNone() {
        assertEquals(Optional.of("&aSteve joined, welcome Steve"),
            Announcements.render("&a{player} joined, welcome {player}", Announcements.DEFAULT_JOIN, "Steve"));
        assertEquals(Optional.of("&eAlex left the server."), Announcements.render(null, Announcements.DEFAULT_LEAVE, "Alex"));
        assertEquals(Optional.empty(), Announcements.render("", Announcements.DEFAULT_JOIN, "Steve"));
        assertEquals(Optional.empty(), Announcements.render("   ", Announcements.DEFAULT_JOIN, "Steve"));
    }
}
