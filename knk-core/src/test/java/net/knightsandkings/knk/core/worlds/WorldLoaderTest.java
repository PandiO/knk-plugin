package net.knightsandkings.knk.core.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** KNG-114: the configured worlds are loaded, created only when asked, and a missing required one fails closed. */
class WorldLoaderTest {

    /** A server with some loaded worlds and some world folders. */
    private static final class FakeHost implements WorldLoader.Host {
        final Set<String> loaded = new HashSet<>(Set.of("world"));
        final Set<String> folders = new HashSet<>(Set.of("world"));
        final Set<String> broken = new HashSet<>();
        final List<WorldSpec> loadCalls = new ArrayList<>();

        @Override
        public boolean isLoaded(String name) {
            return loaded.contains(name);
        }

        @Override
        public boolean folderExists(String name) {
            return folders.contains(name);
        }

        @Override
        public void load(WorldSpec spec) {
            loadCalls.add(spec);
            if (broken.contains(spec.name())) {
                throw new IllegalStateException("corrupt level.dat");
            }
            loaded.add(spec.name());
            folders.add(spec.name());
        }
    }

    private static WorldSpec spec(String name, boolean create, boolean required) {
        return new WorldSpec(name, WorldSpec.Environment.NORMAL, WorldSpec.Generator.VOID, create, required);
    }

    @Test
    void existingFoldersAreLoadedAndNewOnesCreatedOnlyWhenAsked() {
        FakeHost host = new FakeHost();
        host.folders.add("arena");

        WorldReadiness readiness = new WorldLoader(host).loadAll(List.of(
                spec("world", false, true), spec("arena", false, true), spec("hub", true, true), spec("lobby", false, false)));

        assertEquals(List.of(WorldReadiness.State.ALREADY_LOADED, WorldReadiness.State.LOADED, WorldReadiness.State.CREATED,
                WorldReadiness.State.MISSING), readiness.worlds().stream().map(WorldReadiness.Status::state).toList());
        assertEquals(List.of("arena", "hub"), host.loadCalls.stream().map(WorldSpec::name).toList(),
                "the level-name world is not loaded again; a missing folder without create is not created");
        assertTrue(readiness.ready(), "lobby is missing but not required");
    }

    @Test
    void aRequiredWorldThatCannotBeLoadedMakesTheServerNotReady() {
        FakeHost host = new FakeHost();
        host.folders.add("hub");
        host.broken.add("hub");

        WorldReadiness readiness = new WorldLoader(host).loadAll(List.of(spec("hub", false, true), spec("gone", false, true)));

        assertFalse(readiness.ready());
        assertEquals(List.of("hub", "gone"), readiness.unavailableRequired().stream().map(WorldReadiness.Status::name).toList());
        assertEquals("hub: failed - corrupt level.dat", readiness.worlds().get(0).describe());
        assertTrue(readiness.find("HUB").isPresent());
    }

    @Test
    void theConfigListsWorldsWithDefaultsAndSkipsBadEntries() {
        KnkWorldsConfig config = KnkWorldsConfig.fromMap(Map.of("load", List.of(
                Map.of("name", "hub", "environment", "normal", "generator", "void", "create", true),
                "arena",
                Map.of("name", "../escape"),
                Map.of("name", "Hub"),
                Map.of("name", "deep", "environment", "the-end", "generator", "lava", "required", false))));

        assertEquals(List.of(
                new WorldSpec("hub", WorldSpec.Environment.NORMAL, WorldSpec.Generator.VOID, true, true),
                new WorldSpec("arena", WorldSpec.Environment.NORMAL, WorldSpec.Generator.DEFAULT, false, true),
                new WorldSpec("deep", WorldSpec.Environment.THE_END, WorldSpec.Generator.DEFAULT, false, false)),
                config.worlds());
        assertEquals(3, config.warnings().size(), config.warnings().toString());
        assertTrue(KnkWorldsConfig.fromMap(null).worlds().isEmpty());
        assertEquals(1, KnkWorldsConfig.fromMap(Map.of("load", "hub")).warnings().size());
    }
}
