package net.knightsandkings.knk.paper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldSettings;

/** The offline copy of the Game Settings and its history (docs/specs/game-settings/DESIGN.md §3.7, §4). */
class GameSettingsStoreTest {

    @TempDir
    Path folder;

    private static KnkGameSettings settings(String join) {
        KnkSpawnReference town = new KnkSpawnReference(KnkSpawnReference.SourceType.TOWN, 4, "Town: Kardenna",
            new KnkLocation(12, "Kardenna", 10.5, 64.0, -3.5, 90f, 0f, "world"));
        KnkWorldSettings world = new KnkWorldSettings("world", "world", "ADVENTURE", true, 6000,
            new KnkWeatherSettings(KnkWeatherSettings.Mode.BLOCKED, null, Set.of(KnkWeather.THUNDER, KnkWeather.RAIN), 1, 2, 3),
            town, new KnkRespawnPolicy(KnkRespawnPolicy.Mode.NEAREST_TOWN, null, 250.0, false));
        return new KnkGameSettings("CustomReference", town, join, "", null, List.of(world), "2026-10-05T11:59:00",
            "&6Knights and Kings\n&e{online} online",
            List.of(new KnkGroupOverride(2, "Noble", 1, "&6[{group}] {player}", town,
                new KnkRespawnPolicy(KnkRespawnPolicy.Mode.JOIN_SPAWN, null, null, true))));
    }

    @Test
    void roundTripsEverything() {
        GameSettingsStore store = new GameSettingsStore(folder, 5);
        KnkGameSettings saved = settings("&a{player} joined");

        store.save(saved);

        assertEquals(saved, new GameSettingsStore(folder, 5).load().orElseThrow());
    }

    @Test
    void nothingCachedYet() {
        assertTrue(new GameSettingsStore(folder, 5).load().isEmpty());
    }

    @Test
    void aBrokenCacheFileIsIgnored() throws IOException {
        Files.writeString(folder.resolve(GameSettingsStore.CACHE_FILE), "{ not json");

        assertTrue(new GameSettingsStore(folder, 5).load().isEmpty());
    }

    @Test
    void replacedVersionsGoToTheHistoryUpToTheLimit() throws Exception {
        GameSettingsStore store = new GameSettingsStore(folder, 2);
        for (int i = 0; i < 5; i++) {
            store.save(settings("version " + i));
            Thread.sleep(5);
        }

        assertEquals("version 4", store.load().orElseThrow().joinAnnouncement());
        try (Stream<Path> history = Files.list(folder.resolve(GameSettingsStore.HISTORY_DIR))) {
            List<Path> files = history.sorted().toList();
            assertEquals(2, files.size());
            assertTrue(Files.readString(files.get(1)).contains("version 3"));
            assertTrue(Files.readString(files.get(0)).contains("version 2"));
        }
    }

    @Test
    void historyLimitZeroKeepsNone() {
        GameSettingsStore store = new GameSettingsStore(folder, 0);
        store.save(settings("a"));
        store.save(settings("b"));

        assertFalse(Files.exists(folder.resolve(GameSettingsStore.HISTORY_DIR)));
        assertEquals("b", store.load().orElseThrow().joinAnnouncement());
    }

    @Test
    void configDefaultsAndLimits() {
        assertEquals(GameSettingsConfig.defaults(), GameSettingsConfig.from(new YamlConfiguration()));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("game-settings.refresh-interval-seconds", 1);
        yaml.set("game-settings.runtime-sync-interval-seconds", 120);
        yaml.set("game-settings.backup-history-limit", -3);
        assertEquals(new GameSettingsConfig(5, 120, 0), GameSettingsConfig.from(yaml));
    }

    // ===== hub DESIGN §16.4 decision 7: the snapshot lives in the KNG-58 offline store =====

    private net.knightsandkings.knk.core.offline.OfflineSecurityStore offlineStore() {
        var store = new net.knightsandkings.knk.core.offline.OfflineSecurityStore(folder.resolve("offline-security.json"),
            net.knightsandkings.knk.core.offline.OfflineSecurityStore.Settings.defaults(), java.time.Clock.systemUTC());
        store.load();
        return store;
    }

    @Test
    void theSnapshotWithTheHubLivesInTheOfflineStoreAndSurvivesARestart() {
        KnkGameSettings base = settings("&a{player} joined");
        var hub = new net.knightsandkings.knk.core.domain.settings.KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4",
            base.joinSpawnReference(), net.knightsandkings.knk.core.domain.settings.KnkHubSettings.OutageAction.KICK, 30,
            net.knightsandkings.knk.core.domain.settings.KnkHubRules.defaults());
        KnkGameSettings saved = new KnkGameSettings(base.joinSpawnMode(), base.joinSpawnReference(), base.joinAnnouncement(),
            base.leaveAnnouncement(), base.defaultRespawnPolicy(), base.worldSettings(), base.updatedAt(), base.motd(),
            base.groupOverrides(), hub);

        new GameSettingsStore(folder, 5, offlineStore()).save(saved);

        assertFalse(Files.exists(folder.resolve(GameSettingsStore.CACHE_FILE)), "no second copy");
        assertEquals(saved, new GameSettingsStore(folder, 5, offlineStore()).load().orElseThrow(), "after a restart");
    }

    @Test
    void anOldCacheFileIsReadOnceThenMovedToTheHistory() throws IOException {
        new GameSettingsStore(folder, 5).save(settings("old"));
        GameSettingsStore store = new GameSettingsStore(folder, 5, offlineStore());

        assertEquals("old", store.load().orElseThrow().joinAnnouncement(), "migrated from game-settings-cache.json");
        store.save(settings("new"));

        assertFalse(Files.exists(folder.resolve(GameSettingsStore.CACHE_FILE)));
        try (Stream<Path> history = Files.list(folder.resolve(GameSettingsStore.HISTORY_DIR))) {
            assertEquals(1, history.count(), "the old snapshot is kept in the history");
        }
        assertEquals("new", new GameSettingsStore(folder, 5, offlineStore()).load().orElseThrow().joinAnnouncement());
    }
}
