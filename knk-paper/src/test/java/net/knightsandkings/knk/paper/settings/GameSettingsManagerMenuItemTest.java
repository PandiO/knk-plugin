package net.knightsandkings.knk.paper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;

/**
 * Siege shop L6 (PandiO/knk-workspace#871, P26, A29/A31): the menu item gets the {@code menuItem} section of every
 * API answer, never of the cached settings a start with the API down uses.
 */
class GameSettingsManagerMenuItemTest {

    @TempDir
    Path folder;

    private ServerMock server;
    private Plugin plugin;
    private final GameSettingsQueryApi query = mock(GameSettingsQueryApi.class);
    private final List<KnkMenuItemSettings> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("KnightsAndKings");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static KnkGameSettings withMenuItem(String name) {
        KnkGameSettings base = new KnkGameSettings(KnkGameSettings.WORLD_SPAWN, null);
        return new KnkGameSettings(base.joinSpawnMode(), null, null, null, null, List.of(), "2026-11-21T10:00:00", null,
            List.of(), null, null, new KnkMenuItemSettings(true, "all", true, true, "NETHER_STAR", name, List.of(), 8,
                false, false, Map.of("LEFT_CLICK", new KnkMenuItemSettings.Action("OpenMenu", "main", null)), Map.of()));
    }

    private GameSettingsManager manager(GameSettingsStore store) {
        GameSettingsManager manager = new GameSettingsManager(plugin, query, null, () -> null, null,
            GameSettingsConfig.defaults(), store);
        manager.setMenuItemListener(received::add);
        return manager;
    }

    @Test
    void everyApiAnswerPassesTheSectionOn_A29() {
        GameSettingsManager manager = manager(new GameSettingsStore(folder, 0));
        manager.accept(withMenuItem("a"));
        manager.accept(withMenuItem("b"));
        manager.accept(new KnkGameSettings(KnkGameSettings.WORLD_SPAWN, null)); // an older API: nothing to pass

        assertEquals(List.of("a", "b"), received.stream().map(KnkMenuItemSettings::name).toList());
    }

    @Test
    void withTheApiDownAtStartupTheCachedSectionIsNotUsed_SoConfigYmlStays_A31() throws Exception {
        GameSettingsStore store = new GameSettingsStore(folder, 0);
        store.save(withMenuItem("cached"));
        when(query.get()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("API down")));
        GameSettingsManager manager = manager(store);

        manager.start();
        server.getScheduler().performTicks(2);
        server.getScheduler().waitAsyncTasksFinished();
        manager.stop();

        assertEquals("cached", manager.current().menuItem().name(), "the rest of the game settings use the cache");
        assertTrue(received.isEmpty(), "the menu item keeps menus.menu-item from config.yml");
    }
}
